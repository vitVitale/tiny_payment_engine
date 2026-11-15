package com.tpe.iso20022model;

import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.iso20022model.Pacs008Builder.AccountHolder;
import com.tpe.iso20022model.Pacs008Builder.CreditTransfer;
import com.tpe.iso20022model.Pacs028Builder.StatusRequest;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.time.LocalDate;

/** The builder inputs of the golden envelopes in {@code src/test/resources/golden/envelope}. */
final class GoldenInputs {

    static final Bic OWN_BIC = new Bic("TPEBDEFFXXX");
    static final Bic PEER_BIC = new Bic("PEERDEFFXXX");
    static final MsgId PACS_008_MSG_ID = new MsgId("TPEM01JAQ5V6K1W8Y3Z4X9R2T7M5NB");
    static final TxId TX_ID = new TxId("TPE01JAQ5V6K1W8Y3Z4X9R2T7M5NA");
    static final EndToEndId END_TO_END_ID = new EndToEndId("INV-2026-0001");
    static final Uetr UETR = new Uetr("eb6305c9-1f7f-49de-aed0-16487c27b42d");

    private GoldenInputs() {}

    /** {@code envelope/pacs.008.001.08.xml}: our outgoing transfer of EUR 123.45 to the peer. */
    static CreditTransfer creditTransfer() {
        return new CreditTransfer(
                PACS_008_MSG_ID,
                Instant.parse("2026-10-21T09:15:30.123Z"),
                TX_ID,
                END_TO_END_ID,
                UETR,
                Money.parse("123.45"),
                LocalDate.parse("2026-10-21"),
                Instant.parse("2026-10-21T09:15:30.100Z"),
                new AccountHolder(new PartyName("Hans Mueller"), new Iban("DE36100100100000000001")),
                OWN_BIC,
                PEER_BIC,
                new AccountHolder(new PartyName("Erika Musterfrau"), new Iban("DE07200200200123456789")),
                new RemittanceInformation("Invoice 2026-0001"));
    }

    /** {@code envelope/pacs.002.001.10.xml}: the peer rejects our transfer with {@code AM04}. */
    static StatusReport statusReport() {
        return new StatusReport(
                new MsgId("TPEM01JAQ5V7B2C3D4E5F6G7H8J9KM"),
                Instant.parse("2026-10-21T09:15:31.456Z"),
                PACS_008_MSG_ID,
                CsmMessageType.PACS_008,
                TX_ID,
                END_TO_END_ID,
                UETR,
                OWN_BIC,
                Status.rejected(ReasonCode.AM04),
                PEER_BIC,
                OWN_BIC);
    }

    /** {@code envelope/pacs.028.001.03.xml}: we ask the peer for the status of our transfer. */
    static StatusRequest statusRequest() {
        return new StatusRequest(
                new MsgId("TPEM01JAQ5W0P1Q2R3S4T5V6W7X8YZ"),
                Instant.parse("2026-10-21T09:15:52.000Z"),
                PACS_008_MSG_ID,
                TX_ID,
                END_TO_END_ID,
                UETR,
                OWN_BIC,
                OWN_BIC,
                PEER_BIC);
    }
}
