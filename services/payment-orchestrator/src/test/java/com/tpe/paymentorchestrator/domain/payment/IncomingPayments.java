package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.util.UUID;

/** Incoming payments of the unit tests, received at {@link #RECEIVED_AT} with an answer deadline 5 s later. */
public final class IncomingPayments {

    public static final Instant RECEIVED_AT = Instant.parse("2026-11-13T10:00:00Z");
    public static final Bic PEER_BIC = new Bic("PEERDEFFXXX");
    public static final Bic OWN_BIC = new Bic("TPEBDEFFXXX");

    private IncomingPayments() {}

    /** {@code RECEIVED} from the peer bank, its {@code ASSESS_RISK} pending. */
    public static Payment received() {
        return Payment.received(new PaymentId(UUID.randomUUID()), new TxId("PEER01JABCDEFGHJKMNPQRSTVWX"),
                new MsgId("M-PEER-1"), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"), new EndToEndId("E2E-1"),
                Money.parse("12.34"),
                new PaymentParty(new PartyName("Hans Mueller"), new Iban("DE07200200200123456789"), PEER_BIC),
                new PaymentParty(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"), OWN_BIC),
                new RemittanceInformation("Invoice 7"), RECEIVED_AT, RECEIVED_AT.plusSeconds(5), null);
    }

    /** {@code ACCEPT_REQUESTED}: the pending credit exists and {@code AcceptCreditTransfer} is in the outbox. */
    public static Payment acceptRequested() {
        return received().incomingRiskChecked().acceptRequested(UUID.randomUUID());
    }

    /** An outgoing payment, for the moves an incoming one alone makes. */
    public static Payment outgoing() {
        Payment incoming = received();
        return new Payment(incoming.paymentId(), PaymentDirection.OUTGOING, PaymentState.RECEIVED, null, false, null,
                null, null, incoming.txId(), incoming.uetr(), incoming.endToEndId(), incoming.amount(),
                incoming.debtor(), incoming.creditor(), null, null, null, RECEIVED_AT, RECEIVED_AT.plusSeconds(10),
                RECEIVED_AT.plusSeconds(20), PendingAction.ASSESS_RISK, null, null, null, null, null, null);
    }
}
