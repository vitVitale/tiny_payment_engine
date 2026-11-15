package com.tpe.iso20022model;

import static com.tpe.iso20022model.GoldenSamples.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.iso20022model.Pacs008Builder.CreditTransfer;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.iso20022model.pacs008v08.CreditTransferTransaction39;
import com.tpe.iso20022model.pacs028v03.PaymentTransaction113;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CsmBuildersTest {

    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final IsoCodec iso = new IsoCodec();
    private final Pacs008Builder pacs008 = new Pacs008Builder();
    private final Pacs002Builder pacs002 = new Pacs002Builder();
    private final Pacs028Builder pacs028 = new Pacs028Builder();

    static Stream<Arguments> goldenBuilds() {
        return Stream.of(
                Arguments.of("pacs.008.001.08", new Pacs008Builder().build(GoldenInputs.creditTransfer())),
                Arguments.of("pacs.002.001.10", new Pacs002Builder().build(GoldenInputs.statusReport())),
                Arguments.of("pacs.028.001.03", new Pacs028Builder().build(GoldenInputs.statusRequest())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenBuilds")
    void theGoldenInputsBuildTheGoldenEnvelope(String version, CsmEnvelope built) {
        CsmEnvelope golden = accepted(envelopes.split(golden("envelope/" + version + ".xml")));

        assertThat(built.messageType().version()).isEqualTo(version);
        assertThat(built.header()).isEqualTo(golden.header());
        assertThat(built.document()).isEqualTo(golden.document());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenBuilds")
    void ISO_AC_03_aBuiltGoldenSampleSurvivesTheRoundTrip(String version, CsmEnvelope built) {
        Object document = built.document();

        assertThat(iso.unmarshal(iso.marshal(document), document.getClass())).isEqualTo(document);
        assertThat(accepted(envelopes.split(envelopes.join(built)))).isEqualTo(built);
    }

    @Test
    void aStatusReportForAnUnknownTransactionIsRejectedWithTheProprietaryReasonUnknownTx() {
        StatusReport answer = withStatus(GoldenInputs.statusReport(), Status.UNKNOWN_TRANSACTION);

        PaymentTransaction110 transaction = statusOf(pacs002.build(answer));

        assertThat(transaction.getTxSts()).isEqualTo("RJCT");
        assertThat(transaction.getStsRsnInf()).singleElement().satisfies(information -> {
            assertThat(information.getRsn().getPrtry()).isEqualTo("UNKNOWN_TX").isEqualTo(Status.UNKNOWN_TX_REASON);
            assertThat(information.getRsn().getCd()).isNull();
        });
    }

    @Test
    void aFormatRejectionOfAnUnreadableTransactionLeavesOutWhatCouldNotBeRead() {
        // specs/15-csm-gateway.md §6.2: an invalid pacs.008 whose TxId is readable is answered RJCT FF01
        StatusReport golden = GoldenInputs.statusReport();
        StatusReport answer = new StatusReport(golden.msgId(), golden.createdAt(), new MsgId("NOTPROVIDED"),
                CsmMessageType.PACS_008, golden.originalTxId(), null, null, null,
                Status.rejected(ReasonCode.FF01), golden.instructingAgent(), golden.instructedAgent());

        CsmEnvelope built = pacs002.build(answer);
        PaymentTransaction110 transaction = statusOf(built);

        assertThat(transaction.getOrgnlEndToEndId()).isNull();
        assertThat(transaction.getOrgnlUETR()).isNull();
        assertThat(transaction.getOrgnlTxRef()).isNull();
        assertThat(transaction.getStsRsnInf().get(0).getRsn().getCd()).isEqualTo("FF01");
        assertThat(accepted(envelopes.split(envelopes.join(built)))).isEqualTo(built);
    }

    @Test
    void aStatusReportCarriesTheAcceptedAndSettledStatusesWithoutAReason() {
        PaymentTransaction110 accepted =
                statusOf(pacs002.build(withStatus(GoldenInputs.statusReport(), Status.ACCEPTED)));
        PaymentTransaction110 settled =
                statusOf(pacs002.build(withStatus(GoldenInputs.statusReport(), Status.SETTLED)));

        assertThat(accepted.getTxSts()).isEqualTo("ACCP");
        assertThat(accepted.getStsRsnInf()).isEmpty();
        assertThat(settled.getTxSts()).isEqualTo("ACSC");
        assertThat(settled.getStsRsnInf()).isEmpty();
    }

    @Test
    void aStatusReportCarriesTheDebtorAgentForCorrelation() {
        PaymentTransaction110 transaction = statusOf(pacs002.build(GoldenInputs.statusReport()));

        assertThat(transaction.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI())
                .isEqualTo(GoldenInputs.OWN_BIC.value());
    }

    @Test
    void aStatusReportAnsweringAStatusRequestNamesTheRequestAsTheOriginalMessage() {
        StatusReport golden = GoldenInputs.statusReport();
        MsgId request = new MsgId("TPEM01JAQ5W0P1Q2R3S4T5V6W7X8YZ");
        StatusReport answer = new StatusReport(golden.msgId(), golden.createdAt(), request, CsmMessageType.PACS_028,
                golden.originalTxId(), golden.originalEndToEndId(), golden.originalUetr(), golden.debtorAgent(),
                Status.SETTLED, golden.instructingAgent(), golden.instructedAgent());

        var document = (com.tpe.iso20022model.pacs002v10.Document) pacs002.build(answer).document();

        assertThat(document.getFIToFIPmtStsRpt().getOrgnlGrpInfAndSts().get(0).getOrgnlMsgId()).isEqualTo(request.value());
        assertThat(document.getFIToFIPmtStsRpt().getOrgnlGrpInfAndSts().get(0).getOrgnlMsgNmId()).isEqualTo("pacs.028.001.03");
    }

    @Test
    void aRejectedStatusNeedsAReason() {
        assertThatThrownBy(() -> Status.rejected(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aCreditTransferWithoutRemittanceInformationHasNoRmtInf() {
        CreditTransfer golden = GoldenInputs.creditTransfer();
        CreditTransfer withoutRemittance = new CreditTransfer(golden.msgId(), golden.createdAt(), golden.txId(),
                golden.endToEndId(), golden.uetr(), golden.amount(), golden.settlementDate(), golden.acceptedAt(),
                golden.debtor(), golden.debtorAgent(), golden.creditorAgent(), golden.creditor(), null);

        CsmEnvelope built = pacs008.build(withoutRemittance);
        CreditTransferTransaction39 transaction =
                ((com.tpe.iso20022model.pacs008v08.Document) built.document()).getFIToFICstmrCdtTrf().getCdtTrfTxInf().get(0);

        assertThat(transaction.getRmtInf()).isNull();
        assertThat(accepted(envelopes.split(envelopes.join(built)))).isEqualTo(built);
    }

    @Test
    void aStatusRequestCarriesTheDebtorAgentOfTheOriginalTransaction() {
        var document = (com.tpe.iso20022model.pacs028v03.Document) pacs028.build(GoldenInputs.statusRequest()).document();
        PaymentTransaction113 transaction = document.getFIToFIPmtStsReq().getTxInf().get(0);

        assertThat(transaction.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI())
                .isEqualTo(GoldenInputs.OWN_BIC.value());
    }

    @Test
    void theHeaderIsDatedWithTheCreationInstantInUtcMilliseconds() {
        CreditTransfer golden = GoldenInputs.creditTransfer();
        CreditTransfer later = new CreditTransfer(golden.msgId(), Instant.parse("2026-10-21T23:59:59Z"),
                golden.txId(), golden.endToEndId(), golden.uetr(), golden.amount(), golden.settlementDate(),
                golden.acceptedAt(), golden.debtor(), golden.debtorAgent(), golden.creditorAgent(), golden.creditor(),
                golden.remittance());

        String joined = envelopes.join(pacs008.build(later));

        assertThat(joined).contains("<CreDt>2026-10-21T23:59:59.000Z</CreDt>");
    }

    private static StatusReport withStatus(StatusReport report, Status status) {
        return new StatusReport(report.msgId(), report.createdAt(), report.originalMsgId(),
                report.originalMessageType(), report.originalTxId(), report.originalEndToEndId(),
                report.originalUetr(), report.debtorAgent(), status, report.instructingAgent(),
                report.instructedAgent());
    }

    private static PaymentTransaction110 statusOf(CsmEnvelope envelope) {
        var document = (com.tpe.iso20022model.pacs002v10.Document) envelope.document();
        return document.getFIToFIPmtStsRpt().getTxInfAndSts().get(0);
    }

    private static CsmEnvelope accepted(EnvelopeCodec.SplitResult result) {
        assertThat(result).isInstanceOf(Accepted.class);
        return ((Accepted) result).envelope();
    }
}
