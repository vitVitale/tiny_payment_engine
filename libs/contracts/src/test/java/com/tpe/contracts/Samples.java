package com.tpe.contracts;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** The messages of the golden samples in {@code src/test/resources/golden}, current version. */
final class Samples {

    static final PaymentId OUTGOING_PAYMENT = new PaymentId(UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10"));
    static final PaymentId INCOMING_PAYMENT = new PaymentId(UUID.fromString("0192f0e4-7c2e-7d4b-8a66-5d2a4c3f0e21"));
    static final TxId OUTGOING_TX = new TxId("TPE01JAB3C4D5E6F7G8H9J0K1M2NP");
    static final TxId INCOMING_TX = new TxId("PEER20261021000017");
    static final Bic OUR_BIC = new Bic("TPEBDEFFXXX");
    static final Bic PEER_BIC = new Bic("PEERDEFFXXX");

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
                new Party(new PartyName("Anna Schmidt"), new Iban("DE89370400440532013000"),
                        UUID.fromString("0192f0e4-1111-7aaa-8bbb-000000000001")),
                OUR_BIC,
                new Party(new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"), null),
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
}
