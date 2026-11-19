package com.tpe.systemtests.harness;

import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs008Builder;
import com.tpe.iso20022model.Pacs008Builder.AccountHolder;
import com.tpe.iso20022model.Pacs008Builder.CreditTransfer;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.UlidGenerator;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;

/**
 * Incoming {@code pacs.008} a test sends to csm-gateway as the CSM would, through the simulator's raw endpoint
 * (specs/17-csm-simulator.md §3.5): E2E-I-09's invalid ones. 12.34 EUR from a customer of the peer bank
 * ({@code PEERDEFFXXX}) to {@code creditorIban} at ours ({@code TPEBDEFFXXX}); the debtor's name is
 * {@value #DEBTOR_NAME}. The simulator does not play a raw message: it neither settles nor confirms it.
 */
public final class IncomingCreditTransfers {

    /** The debtor's name in every message built here: the place E2E-I-09's DOCTYPE variant puts its entity. */
    static final String DEBTOR_NAME = "Test Raw Debtor";

    private static final EnvelopeCodec ENVELOPES = new EnvelopeCodec();
    private static final Pacs008Builder CREDIT_TRANSFERS = new Pacs008Builder();
    private static final UlidGenerator ULIDS = new UlidGenerator(Clock.systemUTC(), new Random());

    private IncomingCreditTransfers() {}

    /** A valid message of transaction {@code txId}: what the others change. */
    static String valid(String txId, String creditorIban) {
        Instant now = Instant.now();
        return ENVELOPES.join(CREDIT_TRANSFERS.build(new CreditTransfer(new MsgId("SYSTEST" + ULIDS.next()), now,
                new TxId(txId), new EndToEndId("SYSTEST" + ULIDS.next()), new Uetr(UUID.randomUUID().toString()),
                Money.parse("12.34"), LocalDate.ofInstant(now, ZoneOffset.UTC), now.minusMillis(20),
                new AccountHolder(new PartyName(DEBTOR_NAME), new Iban(TestData.peerIban())), new Bic("PEERDEFFXXX"),
                new Bic("TPEBDEFFXXX"), new AccountHolder(new PartyName(TestData.newCustomer().name()),
                        new Iban(creditorIban)), null)));
    }

    /**
     * Schema-invalid (its mandatory {@code ChrgBr} left out, after the transaction's identity), so csm-gateway reads
     * its {@code TxId} and {@code DbtrAgt} and answers it {@code RJCT FF01} (15 §6.2 step 2).
     */
    public static String schemaInvalid(String txId, String creditorIban) {
        return valid(txId, creditorIban).replace("<ChrgBr>SLEV</ChrgBr>", "");
    }

    /**
     * Valid but for a DOCTYPE that declares the external entity {@code xxe} at {@code entity}, which the debtor's name
     * references: a reader that resolved it would put what is there into the message (GW-AC-14).
     */
    public static String withDoctype(String txId, String creditorIban, URI entity) {
        return valid(txId, creditorIban)
                .replaceFirst("<Envelope ", "<!DOCTYPE Envelope [<!ENTITY xxe SYSTEM \"" + entity + "\">]><Envelope ")
                .replace("<Nm>" + DEBTOR_NAME + "</Nm>", "<Nm>&xxe;</Nm>");
    }
}
