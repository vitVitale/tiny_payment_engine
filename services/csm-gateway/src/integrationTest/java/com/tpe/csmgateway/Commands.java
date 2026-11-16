package com.tpe.csmgateway;

import com.tpe.contracts.Party;
import com.tpe.contracts.SendCreditTransfer;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Commands as payment-orchestrator sends them (specs/02-messaging-contracts.md §3.1). */
final class Commands {

    static final Bic OWN_BIC = new Bic("TPEBDEFFXXX");
    static final Bic PEER_BIC = new Bic("PEERDEFFXXX");

    private Commands() {}

    /** Accepted at {@code acceptedAt}, {@code sendBy} 10 s and {@code deadlineAt} 20 s later. */
    static SendCreditTransfer sendCreditTransfer(String txId, Instant acceptedAt) {
        return new SendCreditTransfer(
                new PaymentId(UUID.randomUUID()),
                new TxId(txId),
                new Uetr(UUID.randomUUID().toString()),
                new EndToEndId("E2E-" + txId),
                acceptedAt,
                acceptedAt.plus(Duration.ofSeconds(10)),
                acceptedAt.plus(Duration.ofSeconds(20)),
                Money.parse("123.45"),
                new Party(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"), UUID.randomUUID()),
                OWN_BIC,
                new Party(new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"), null),
                PEER_BIC,
                new RemittanceInformation("Invoice " + txId));
    }
}
