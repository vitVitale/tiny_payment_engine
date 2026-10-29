package com.tpe.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.ContractViolationException.Violation;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ContractValidatorTest {

    private static final Instant T0 = Instant.parse("2026-10-21T09:15:30Z");

    @Test
    void validMessagesPass() {
        GoldenSampleTest.messages().forEach(message ->
                assertThatCode(() -> ContractValidator.validate(message)).doesNotThrowAnyException());
    }

    @ParameterizedTest(name = "acceptedAt +{0}s, sendBy +{1}s, deadlineAt +{2}s")
    @CsvSource({"10, 10, 20", "0, 20, 20", "0, 30, 20", "20, 10, 30", "0, 0, 0"})
    void sendCreditTransferRequiresAcceptedAtBeforeSendByBeforeDeadlineAt(int accepted, int sendBy, int deadline) {
        SendCreditTransfer message = withTimes(T0.plusSeconds(accepted), T0.plusSeconds(sendBy),
                T0.plusSeconds(deadline));

        assertThatThrownBy(() -> ContractValidator.validate(message))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .extracting(Violation::code)
                        .containsExactly(ContractViolationException.OUT_OF_ORDER));
    }

    @Test
    void sendCreditTransferWithStrictlyIncreasingTimesPasses() {
        assertThatCode(() -> ContractValidator.validate(withTimes(T0, T0.plusMillis(1), T0.plusMillis(2))))
                .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredFieldsAreReportedWithTheirPaths() {
        SendCreditTransfer s = Samples.sendCreditTransfer();
        var message = new SendCreditTransfer(s.paymentId(), null, s.uetr(), s.endToEndId(), s.acceptedAt(),
                s.sendBy(), s.deadlineAt(), null, new Party(new PartyName("Anna Schmidt"), null, null),
                s.debtorAgentBic(), s.creditor(), s.creditorAgentBic(), s.remittanceInformation());

        assertThatThrownBy(() -> ContractValidator.validate(message))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(
                                new Violation("txId", ContractViolationException.REQUIRED),
                                new Violation("amount", ContractViolationException.REQUIRED),
                                new Violation("debtor.iban", ContractViolationException.REQUIRED)));
    }

    @Test
    void missingOrderedTimeIsRequiredNotOutOfOrder() {
        SendCreditTransfer message = withTimes(T0, null, T0.plusSeconds(20));

        assertThatThrownBy(() -> ContractValidator.validate(message))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactly(new Violation("sendBy", ContractViolationException.REQUIRED)));
    }

    @Test
    void optionalFieldsMayBeAbsent() {
        SendCreditTransfer s = Samples.sendCreditTransfer();
        var message = new SendCreditTransfer(s.paymentId(), s.txId(), s.uetr(), s.endToEndId(), s.acceptedAt(),
                s.sendBy(), s.deadlineAt(), s.amount(), new Party(s.debtor().name(), s.debtor().iban(), null),
                s.debtorAgentBic(), s.creditor(), s.creditorAgentBic(), null);

        assertThatCode(() -> ContractValidator.validate(message)).doesNotThrowAnyException();
    }

    @Test
    void statusRequestAttemptStartsAtOne() {
        RequestPaymentStatus r = Samples.requestPaymentStatus();
        var message = new RequestPaymentStatus(r.paymentId(), r.txId(), r.debtorAgentBic(), r.direction(), 0,
                r.requestedAt());

        assertThatThrownBy(() -> ContractValidator.validate(message))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactly(new Violation("attempt", ContractViolationException.OUT_OF_RANGE)));
    }

    @Test
    void violationMessageNamesTypeFieldsAndCodesOnly() {
        RejectCreditTransfer r = Samples.rejectCreditTransfer();
        var message = new RejectCreditTransfer(r.paymentId(), r.txId(), r.debtorAgentBic(), r.decidedAt(), null);

        assertThatThrownBy(() -> ContractValidator.validate(message))
                .hasMessage("RejectCreditTransfer v1 violates its contract: [reasonCode REQUIRED]");
    }

    static Stream<AccountEvent> accountEvents() {
        return Stream.of(Samples.accountOpened(), Samples.accountBlocked(), Samples.accountUnblocked(),
                Samples.accountClosed());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accountEvents")
    void accountEventRequiresCustomerId(AccountEvent event) {
        AccountSnapshot a = event.account();
        var account = new AccountSnapshot(a.iban(), null, a.status(), a.activeBlockReasons(), a.kind(), a.holder(),
                a.openedAt(), a.closedAt());

        assertViolations(Samples.withAccount(event, account),
                new Violation("account.customerId", ContractViolationException.REQUIRED));
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
        "'   ', REQUIRED",
        "CUST 000123, INVALID_CUSTOMER_ID",
        "CUST-0001234567890123456789012345678901234567890123, INVALID_CUSTOMER_ID" // 51 characters
    })
    void customerIdIsTheBanksCustomerNumber(String customerId, String code) {
        AccountOpened o = Samples.accountOpened();
        AccountSnapshot a = o.account();
        var account = new AccountSnapshot(a.iban(), customerId, a.status(), a.activeBlockReasons(), a.kind(),
                a.holder(), a.openedAt(), a.closedAt());

        assertViolations(Samples.withAccount(o, account), new Violation("account.customerId", code));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accountEvents")
    void technicalAccountsAreNotPublished(AccountEvent event) {
        AccountSnapshot a = event.account();
        var account = new AccountSnapshot(a.iban(), a.customerId(), a.status(), a.activeBlockReasons(),
                AccountKind.TECHNICAL, a.holder(), a.openedAt(), a.closedAt());

        assertViolations(Samples.withAccount(event, account),
                new Violation("account.kind", ContractViolationException.NOT_ALLOWED));
    }

    @Test
    void accountRevisionStartsAtOne() {
        AccountOpened o = Samples.accountOpened();

        assertViolations(new AccountOpened(o.accountId(), 0L, o.occurredAt(), o.account()),
                new Violation("revision", ContractViolationException.OUT_OF_RANGE));
    }

    @Test
    void organisationHolderRequiresOrganisationIdAndNoDateOfBirth() {
        AccountOpened o = Samples.accountOpened();
        Holder person = o.account().holder();
        var organisation = new Holder(HolderType.ORGANISATION, person.name(), person.dateOfBirth(), "DE", null);

        assertViolations(Samples.withAccount(o, withHolder(o.account(), organisation)),
                new Violation("account.holder.organisationId", ContractViolationException.REQUIRED),
                new Violation("account.holder.dateOfBirth", ContractViolationException.NOT_ALLOWED));
        var valid = new Holder(HolderType.ORGANISATION, person.name(), null, "DE", "529900T8BM49AURSDO55");
        assertThatCode(() -> ContractValidator.validate(Samples.withAccount(o, withHolder(o.account(), valid))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectedAnswerRequiresReasonCode() {
        AnswerSent a = Samples.answerSent();

        assertViolations(new AnswerSent(a.txId(), a.debtorAgentBic(), a.direction(), AnswerStatus.RJCT, null,
                a.decidedBy(), a.sentAt()), new Violation("reasonCode", ContractViolationException.REQUIRED));
    }

    @Test
    void acceptedAnswerHasNoReasonCode() {
        AnswerSent a = Samples.answerSent();

        assertViolations(new AnswerSent(a.txId(), a.debtorAgentBic(), a.direction(), AnswerStatus.ACCP,
                a.reasonCode(), a.decidedBy(), a.sentAt()),
                new Violation("reasonCode", ContractViolationException.NOT_ALLOWED));
        assertThatCode(() -> ContractValidator.validate(new AnswerSent(a.txId(), a.debtorAgentBic(), a.direction(),
                AnswerStatus.ACCP, null, DecidedBy.DEADLINE, a.sentAt()))).doesNotThrowAnyException();
    }

    @Test
    void settledAndRejectedCarryPaymentIdOnlyWhenOutgoing() {
        CreditTransferSettled s = Samples.creditTransferSettled();
        CreditTransferRejected r = Samples.creditTransferRejected();

        assertViolations(new CreditTransferSettled(s.txId(), s.debtorAgentBic(), Direction.OUTGOING, null,
                s.settledAt(), s.source()), new Violation("paymentId", ContractViolationException.REQUIRED));
        assertViolations(new CreditTransferRejected(r.txId(), r.debtorAgentBic(), Direction.OUTGOING, null,
                r.reasonCode(), r.rejectedAt(), r.source()),
                new Violation("paymentId", ContractViolationException.REQUIRED));
        assertViolations(new CreditTransferSettled(s.txId(), s.debtorAgentBic(), Direction.INCOMING, s.paymentId(),
                s.settledAt(), s.source()), new Violation("paymentId", ContractViolationException.NOT_ALLOWED));
        assertThatCode(() -> ContractValidator.validate(new CreditTransferRejected(r.txId(), r.debtorAgentBic(),
                Direction.INCOMING, null, r.reasonCode(), r.rejectedAt(), SettlementSource.INVESTIGATION)))
                .doesNotThrowAnyException();
    }

    @Test
    void notSentReasonIsAlwaysAb03() {
        CreditTransferNotSent n = Samples.creditTransferNotSent();

        assertViolations(new CreditTransferNotSent(n.txId(), n.debtorAgentBic(), n.direction(), n.paymentId(),
                ReasonCode.AC04, n.sendBy(), n.decidedAt()),
                new Violation("reasonCode", ContractViolationException.NOT_ALLOWED));
    }

    @Test
    void oneWayInboundEventsHaveTheirDirection() {
        CreditTransferReceived c = Samples.creditTransferReceived();
        AnswerSent a = Samples.answerSent();
        CreditTransferNotSent n = Samples.creditTransferNotSent();
        Violation wrongDirection = new Violation("direction", ContractViolationException.NOT_ALLOWED);

        assertViolations(new CreditTransferReceived(c.txId(), c.debtorAgentBic(), Direction.OUTGOING, c.msgId(),
                c.uetr(), c.endToEndId(), c.receivedAt(), c.answerDeadline(), c.acceptanceDateTime(),
                c.interbankSettlementDate(), c.amount(), c.debtor(), c.creditor(), c.creditorAgentBic(),
                c.remittanceInformation()), wrongDirection);
        assertViolations(new AnswerSent(a.txId(), a.debtorAgentBic(), Direction.OUTGOING, a.status(),
                a.reasonCode(), a.decidedBy(), a.sentAt()), wrongDirection);
        assertViolations(new CreditTransferNotSent(n.txId(), n.debtorAgentBic(), Direction.INCOMING, n.paymentId(),
                n.reasonCode(), n.sendBy(), n.decidedAt()), wrongDirection);
    }

    @Test
    void receivedAnswerDeadlineIsAfterReceivedAt() {
        CreditTransferReceived c = Samples.creditTransferReceived();

        assertViolations(new CreditTransferReceived(c.txId(), c.debtorAgentBic(), c.direction(), c.msgId(),
                c.uetr(), c.endToEndId(), c.receivedAt(), c.receivedAt(), c.acceptanceDateTime(),
                c.interbankSettlementDate(), c.amount(), c.debtor(), c.creditor(), c.creditorAgentBic(),
                c.remittanceInformation()), new Violation("answerDeadline", ContractViolationException.OUT_OF_ORDER));
    }

    @ParameterizedTest(name = "direction {0}, onUs {1}")
    @CsvSource({"INCOMING, false", "OUTGOING, true"})
    void settledPaymentToOurCustomerNamesTheCreditorAccount(Direction direction, boolean onUs) {
        PaymentSettled p = Samples.paymentSettled();
        var creditor = new Party(p.creditor().name(), p.creditor().iban(), null);

        assertViolations(new PaymentSettled(p.paymentId(), direction, onUs, p.txId(), p.debtorAgentBic(), p.uetr(),
                p.endToEndId(), p.amount(), p.debtor(), creditor, p.remittanceInformation(), p.acceptedAt(),
                p.settledAt(), p.bookingEntryId()),
                new Violation("creditor.accountId", ContractViolationException.REQUIRED));
    }

    @Test
    void unknownConstantsReadFromNewerProducersPassTheRules() {
        // a consumer decides whether it can proceed with UNKNOWN (01 §4); the per-type rules do not reject it
        AnswerSent a = Samples.answerSent();
        CreditTransferSettled s = Samples.creditTransferSettled();

        assertThatCode(() -> ContractValidator.validate(new AnswerSent(a.txId(), a.debtorAgentBic(),
                Direction.UNKNOWN, AnswerStatus.UNKNOWN, a.reasonCode(), a.decidedBy(), a.sentAt())))
                .doesNotThrowAnyException();
        assertThatCode(() -> ContractValidator.validate(new CreditTransferSettled(s.txId(), s.debtorAgentBic(),
                Direction.UNKNOWN, null, s.settledAt(), SettlementSource.UNKNOWN))).doesNotThrowAnyException();
    }

    private static void assertViolations(Message message, Violation... expected) {
        assertThatThrownBy(() -> ContractValidator.validate(message))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(expected));
    }

    private static AccountSnapshot withHolder(AccountSnapshot a, Holder holder) {
        return new AccountSnapshot(a.iban(), a.customerId(), a.status(), a.activeBlockReasons(), a.kind(), holder,
                a.openedAt(), a.closedAt());
    }

    private static SendCreditTransfer withTimes(Instant acceptedAt, Instant sendBy, Instant deadlineAt) {
        SendCreditTransfer s = Samples.sendCreditTransfer();
        return new SendCreditTransfer(s.paymentId(), s.txId(), s.uetr(), s.endToEndId(), acceptedAt, sendBy,
                deadlineAt, s.amount(), s.debtor(), s.debtorAgentBic(), s.creditor(), s.creditorAgentBic(),
                s.remittanceInformation());
    }
}
