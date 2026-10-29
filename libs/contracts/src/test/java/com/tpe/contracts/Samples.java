package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** The messages of the golden samples in {@code src/test/resources/golden}, current version. */
final class Samples {

    static final PaymentId OUTGOING_PAYMENT = new PaymentId(UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10"));
    static final PaymentId INCOMING_PAYMENT = new PaymentId(UUID.fromString("0192f0e4-7c2e-7d4b-8a66-5d2a4c3f0e21"));
    static final TxId OUTGOING_TX = new TxId("TPE01JAB3C4D5E6F7G8H9J0K1M2NP");
    static final TxId INCOMING_TX = new TxId("PEER20261021000017");
    static final Bic OUR_BIC = new Bic("TPEBDEFFXXX");
    static final Bic PEER_BIC = new Bic("PEERDEFFXXX");
    static final UUID ANNA_ACCOUNT = UUID.fromString("0192f0e4-1111-7aaa-8bbb-000000000001");
    static final Party ANNA = new Party(new PartyName("Anna Schmidt"), new Iban("DE89370400440532013000"),
            ANNA_ACCOUNT);
    static final Party MAX = new Party(new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"), null);

    private Samples() {}

    static SendCreditTransfer sendCreditTransfer() {
        return new SendCreditTransfer(
                OUTGOING_PAYMENT,
                OUTGOING_TX,
                new Uetr("8a562c67-ca16-48ba-b074-65581be6f001"),
                new EndToEndId("INV-2026-0042"),
                Instant.parse("2026-10-21T09:15:30Z"),
                Instant.parse("2026-10-21T09:15:40Z"),
                Instant.parse("2026-10-21T09:15:50Z"),
                new Money(12345, Currency.getInstance("EUR")),
                ANNA,
                OUR_BIC,
                MAX,
                PEER_BIC,
                new RemittanceInformation("Invoice 42"));
    }

    static RequestPaymentStatus requestPaymentStatus() {
        return new RequestPaymentStatus(OUTGOING_PAYMENT, OUTGOING_TX, OUR_BIC, Direction.OUTGOING, 1,
                Instant.parse("2026-10-21T09:15:52.250Z"));
    }

    static AcceptCreditTransfer acceptCreditTransfer() {
        return new AcceptCreditTransfer(INCOMING_PAYMENT, INCOMING_TX, PEER_BIC,
                Instant.parse("2026-10-21T09:16:01.125Z"));
    }

    static RejectCreditTransfer rejectCreditTransfer() {
        return new RejectCreditTransfer(INCOMING_PAYMENT, INCOMING_TX, PEER_BIC,
                Instant.parse("2026-10-21T09:16:01.125Z"), ReasonCode.AC06);
    }

    static CreditTransferReceived creditTransferReceived() {
        return new CreditTransferReceived(
                INCOMING_TX,
                PEER_BIC,
                Direction.INCOMING,
                new MsgId("PEERM20261021000017"),
                new Uetr("3f2c1b0a-9d8e-4c7b-a6f5-e4d3c2b1a090"),
                new EndToEndId("RENT-2026-10"),
                Instant.parse("2026-10-21T09:16:00.040Z"),
                Instant.parse("2026-10-21T09:16:20.040Z"),
                Instant.parse("2026-10-21T09:15:59Z"),
                LocalDate.parse("2026-10-21"),
                new Money(5000, Currency.getInstance("EUR")),
                MAX,
                new Party(ANNA.name(), ANNA.iban(), null),
                OUR_BIC,
                new RemittanceInformation("Rent October"));
    }

    static AnswerSent answerSent() {
        return new AnswerSent(INCOMING_TX, PEER_BIC, Direction.INCOMING, AnswerStatus.RJCT, ReasonCode.AC06,
                DecidedBy.VERDICT, Instant.parse("2026-10-21T09:16:01.300Z"));
    }

    static CreditTransferSettled creditTransferSettled() {
        return new CreditTransferSettled(OUTGOING_TX, OUR_BIC, Direction.OUTGOING, OUTGOING_PAYMENT,
                Instant.parse("2026-10-21T09:15:45.500Z"), SettlementSource.STATUS_REPORT);
    }

    static CreditTransferRejected creditTransferRejected() {
        return new CreditTransferRejected(OUTGOING_TX, OUR_BIC, Direction.OUTGOING, OUTGOING_PAYMENT,
                ReasonCode.AC04, Instant.parse("2026-10-21T09:15:45.500Z"), SettlementSource.STATUS_REPORT);
    }

    static CreditTransferNotSent creditTransferNotSent() {
        return new CreditTransferNotSent(OUTGOING_TX, OUR_BIC, Direction.OUTGOING, OUTGOING_PAYMENT,
                ReasonCode.AB03, Instant.parse("2026-10-21T09:15:40Z"), Instant.parse("2026-10-21T09:15:40.010Z"));
    }

    static AccountOpened accountOpened() {
        return new AccountOpened(ANNA_ACCOUNT, 1L, Instant.parse("2026-01-05T08:00:00Z"),
                snapshot(AccountStatus.OPEN, List.of(), null));
    }

    static AccountBlocked accountBlocked() {
        return new AccountBlocked(ANNA_ACCOUNT, 7L, Instant.parse("2026-10-21T02:10:00Z"),
                snapshot(AccountStatus.BLOCKED, List.of(BlockReason.SANCTIONS_PROVISIONAL), null),
                BlockReason.SANCTIONS_PROVISIONAL);
    }

    static AccountUnblocked accountUnblocked() {
        return new AccountUnblocked(ANNA_ACCOUNT, 8L, Instant.parse("2026-10-21T10:30:00Z"),
                snapshot(AccountStatus.OPEN, List.of(), null), BlockReason.SANCTIONS_PROVISIONAL);
    }

    static AccountClosed accountClosed() {
        return new AccountClosed(ANNA_ACCOUNT, 9L, Instant.parse("2026-12-31T16:00:00Z"),
                snapshot(AccountStatus.CLOSED, List.of(), Instant.parse("2026-12-31T16:00:00Z")));
    }

    static AccountSnapshot snapshot(AccountStatus status, List<BlockReason> blocks, Instant closedAt) {
        return new AccountSnapshot(ANNA.iban(), "CUST-000123", status, blocks, AccountKind.CUSTOMER,
                new Holder(HolderType.PERSON, ANNA.name(), LocalDate.parse("1985-04-12"), "DE", null),
                Instant.parse("2026-01-05T08:00:00Z"), closedAt);
    }

    /** {@code event} with {@code account} as its snapshot; same type and other fields. */
    static AccountEvent withAccount(AccountEvent event, AccountSnapshot account) {
        return switch (event) {
            case AccountOpened e -> new AccountOpened(e.accountId(), e.revision(), e.occurredAt(), account);
            case AccountBlocked e ->
                    new AccountBlocked(e.accountId(), e.revision(), e.occurredAt(), account, e.blockReason());
            case AccountUnblocked e ->
                    new AccountUnblocked(e.accountId(), e.revision(), e.occurredAt(), account, e.liftedReason());
            case AccountClosed e -> new AccountClosed(e.accountId(), e.revision(), e.occurredAt(), account);
        };
    }

    static PaymentSettled paymentSettled() {
        return new PaymentSettled(
                OUTGOING_PAYMENT,
                Direction.OUTGOING,
                false,
                OUTGOING_TX,
                OUR_BIC,
                new Uetr("8a562c67-ca16-48ba-b074-65581be6f001"),
                new EndToEndId("INV-2026-0042"),
                new Money(12345, Currency.getInstance("EUR")),
                ANNA,
                MAX,
                new RemittanceInformation("Invoice 42"),
                Instant.parse("2026-10-21T09:15:30Z"),
                Instant.parse("2026-10-21T09:15:45.500Z"),
                UUID.fromString("0192f0e4-9e4f-7f6d-8c88-7f4c6e5b2a43"));
    }

    static PaymentRejected paymentRejected() {
        return new PaymentRejected(
                INCOMING_PAYMENT,
                Direction.INCOMING,
                false,
                INCOMING_TX,
                PEER_BIC,
                new Uetr("3f2c1b0a-9d8e-4c7b-a6f5-e4d3c2b1a090"),
                new EndToEndId("RENT-2026-10"),
                new Money(5000, Currency.getInstance("EUR")),
                MAX,
                ANNA,
                new RemittanceInformation("Rent October"),
                Instant.parse("2026-10-21T09:15:59Z"),
                Instant.parse("2026-10-21T09:16:01.300Z"),
                ReasonCode.AC06);
    }
}
