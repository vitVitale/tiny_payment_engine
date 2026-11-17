package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.iso20022model.pacs002v10.Document;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** E2E-O-14: the CSM's own status report of a transfer, turned into a later one that contradicts it. */
class StatusReportsTest {

    private static final EnvelopeCodec ENVELOPES = new EnvelopeCodec();

    @Test
    void aSettlementContradictsARejectionOfTheSameTransactionUnderANewMsgId() {
        String rejected = report(Status.rejected(ReasonCode.AC04));

        String contradicting = StatusReports.contradicting(rejected, Status.SETTLED);

        PaymentTransaction110 transaction = transaction(contradicting);
        assertThat(transaction.getTxSts()).isEqualTo("ACSC");
        assertThat(transaction.getStsRsnInf()).isEmpty();
        assertThat(transaction.getOrgnlTxId()).isEqualTo("TX-O14");
        assertThat(transaction.getOrgnlEndToEndId()).isEqualTo("E2E-O-14");
        assertThat(transaction.getOrgnlUETR()).isEqualTo("6f1a0c2e-5b7d-4c3e-9a8f-1d2e3f4a5b6c");
        assertThat(transaction.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI()).isEqualTo("TPEBDEFFXXX");
        assertThat(transaction.getInstgAgt().getFinInstnId().getBICFI()).isEqualTo("TIPSXXXXXXX");
        assertThat(transaction.getInstdAgt().getFinInstnId().getBICFI()).isEqualTo("TPEBDEFFXXX");
        var report = document(contradicting).getFIToFIPmtStsRpt();
        assertThat(report.getOrgnlGrpInfAndSts().getFirst().getOrgnlMsgId()).isEqualTo("TPEM-ORIGINAL");
        assertThat(report.getOrgnlGrpInfAndSts().getFirst().getOrgnlMsgNmId())
                .isEqualTo(CsmMessageType.PACS_008.version());
        assertThat(report.getGrpHdr().getMsgId()).isNotEqualTo("SIM-FIRST").startsWith("SYSTEST");
    }

    @Test
    void aRejectionContradictsASettlement() {
        String contradicting = StatusReports.contradicting(report(Status.SETTLED), Status.rejected(ReasonCode.AC04));

        PaymentTransaction110 transaction = transaction(contradicting);
        assertThat(transaction.getTxSts()).isEqualTo("RJCT");
        assertThat(transaction.getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("AC04");
    }

    @Test
    void onlyAStatusReportIsContradicted() {
        assertThatThrownBy(() -> StatusReports.contradicting("<not-an-envelope/>", Status.SETTLED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A status report as the simulator sends it, answering the gateway's {@code pacs.008}. */
    private static String report(Status status) {
        return ENVELOPES.join(new Pacs002Builder().build(new StatusReport(new MsgId("SIM-FIRST"),
                Instant.parse("2026-11-17T10:00:00Z"), new MsgId("TPEM-ORIGINAL"), CsmMessageType.PACS_008,
                new TxId("TX-O14"), new EndToEndId("E2E-O-14"), new Uetr("6f1a0c2e-5b7d-4c3e-9a8f-1d2e3f4a5b6c"),
                new Bic("TPEBDEFFXXX"), status, new Bic("TIPSXXXXXXX"), new Bic("TPEBDEFFXXX"))));
    }

    private static Document document(String xml) {
        return (Document) ((Accepted) ENVELOPES.split(xml)).envelope().document();
    }

    private static PaymentTransaction110 transaction(String xml) {
        return document(xml).getFIToFIPmtStsRpt().getTxInfAndSts().getFirst();
    }
}
