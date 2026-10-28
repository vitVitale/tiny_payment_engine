package com.tpe.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.ContractViolationException.Violation;
import com.tpe.paymentdomain.PartyName;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    private static SendCreditTransfer withTimes(Instant acceptedAt, Instant sendBy, Instant deadlineAt) {
        SendCreditTransfer s = Samples.sendCreditTransfer();
        return new SendCreditTransfer(s.paymentId(), s.txId(), s.uetr(), s.endToEndId(), acceptedAt, sendBy,
                deadlineAt, s.amount(), s.debtor(), s.debtorAgentBic(), s.creditor(), s.creditorAgentBic(),
                s.remittanceInformation());
    }
}
