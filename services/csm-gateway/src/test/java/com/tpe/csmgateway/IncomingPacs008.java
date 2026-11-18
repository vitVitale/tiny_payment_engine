package com.tpe.csmgateway;

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
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * An incoming {@code pacs.008} as the CSM delivers it (specs/15-csm-gateway.md §6.2): from a peer bank's customer to
 * one of ours, {@code CdtrAgt} our BIC.
 */
public final class IncomingPacs008 {

    public static final Bic OWN_BIC = new Bic("TPEBDEFFXXX");
    public static final Bic PEER_BIC = new Bic("PEERDEFFXXX");
    public static final Bic OTHER_BIC = new Bic("OTHRDEFFXXX");
    public static final Iban DEBTOR = new Iban("DE07200200200123456789");
    public static final Iban CREDITOR = new Iban("DE36100100100000000001");

    private static final EnvelopeCodec ENVELOPES = new EnvelopeCodec();
    private static final Pacs008Builder CREDIT_TRANSFERS = new Pacs008Builder();

    private IncomingPacs008() {}

    /** {@code 12.34 EUR} from {@code debtorAgent}, sent with {@code msgId} at {@code createdAt}. */
    public static String pacs008(String msgId, String txId, Bic debtorAgent, Instant createdAt) {
        return pacs008(msgId, txId, debtorAgent, OWN_BIC, createdAt);
    }

    public static String pacs008(String msgId, String txId, Bic debtorAgent, Bic creditorAgent, Instant createdAt) {
        return ENVELOPES.join(CREDIT_TRANSFERS.build(new CreditTransfer(new MsgId(msgId), createdAt,
                new TxId(txId), new EndToEndId("E2E-" + txId), uetr(txId), Money.parse("12.34"),
                LocalDate.ofInstant(createdAt, ZoneOffset.UTC), createdAt.minusMillis(20),
                new AccountHolder(new PartyName("Hans Mueller"), DEBTOR), debtorAgent, creditorAgent,
                new AccountHolder(new PartyName("Anna Schmidt"), CREDITOR),
                new RemittanceInformation("Invoice " + txId))));
    }

    /** {@code xml} without its mandatory {@code ChrgBr}: schema-invalid, its identity still readable. */
    public static String schemaInvalid(String xml) {
        return xml.replace("<ChrgBr>SLEV</ChrgBr>", "");
    }

    /** A UUID v4 derived from {@code txId}, so a retry carries the same one. */
    public static Uetr uetr(String txId) {
        UUID name = UUID.nameUUIDFromBytes(txId.getBytes(StandardCharsets.UTF_8));
        return new Uetr(new UUID((name.getMostSignificantBits() & ~0xF000L) | 0x4000L,
                name.getLeastSignificantBits()).toString());
    }
}
