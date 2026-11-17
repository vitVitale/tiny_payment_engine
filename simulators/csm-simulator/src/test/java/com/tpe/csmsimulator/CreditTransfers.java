package com.tpe.csmsimulator;

import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs008Builder;
import com.tpe.iso20022model.Pacs008Builder.AccountHolder;
import com.tpe.iso20022model.Pacs008Builder.CreditTransfer;
import com.tpe.iso20022model.Pacs028Builder;
import com.tpe.iso20022model.Pacs028Builder.StatusRequest;
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

/** {@code pacs.008} and {@code pacs.028} envelopes as csm-gateway sends them: from our bank. */
public final class CreditTransfers {

    public static final Bic OUR_BIC = new Bic("TPEBDEFFXXX");
    public static final Bic PEER_BIC = new Bic("PEERDEFFXXX");
    public static final Iban CREDITOR = new Iban("DE07200200200123456789");
    public static final Bic CSM_BIC = new Bic("TIPSXXXXXXX");
    public static final Iban DEBTOR = new Iban("DE36100100100000000001");

    private CreditTransfers() {}

    /** A transfer of {@code amount} to {@link #CREDITOR}, accepted (and sent) at {@code acceptedAt}. */
    public static String pacs008(String msgId, String txId, String amount, Instant acceptedAt) {
        return pacs008(msgId, txId, amount, acceptedAt, CREDITOR);
    }

    public static String pacs008(String msgId, String txId, String amount, Instant acceptedAt, Iban creditor) {
        CreditTransfer transfer = new CreditTransfer(
                new MsgId(msgId),
                acceptedAt,
                new TxId(txId),
                new EndToEndId("E2E-" + txId),
                uetr(txId),
                Money.parse(amount),
                LocalDate.ofInstant(acceptedAt, ZoneOffset.UTC),
                acceptedAt,
                new AccountHolder(new PartyName("Hans Mueller"), DEBTOR),
                OUR_BIC,
                PEER_BIC,
                new AccountHolder(new PartyName("Erika Musterfrau"), creditor),
                new RemittanceInformation("Invoice " + txId));
        return new EnvelopeCodec().join(new Pacs008Builder().build(transfer));
    }

    /** A status request for the transfer {@code txId} sent with {@code originalMsgId}. */
    public static String pacs028(String msgId, String originalMsgId, String txId, Instant createdAt) {
        return new EnvelopeCodec().join(new Pacs028Builder().build(new StatusRequest(new MsgId(msgId), createdAt,
                new MsgId(originalMsgId), new TxId(txId), new EndToEndId("E2E-" + txId), uetr(txId), OUR_BIC,
                OUR_BIC, CSM_BIC)));
    }

    /** A UUID v4 derived from {@code txId}, so a retry carries the same one. */
    private static Uetr uetr(String txId) {
        UUID name = UUID.nameUUIDFromBytes(txId.getBytes(StandardCharsets.UTF_8));
        return new Uetr(new UUID((name.getMostSignificantBits() & ~0xF000L) | 0x4000L,
                name.getLeastSignificantBits()).toString());
    }
}
