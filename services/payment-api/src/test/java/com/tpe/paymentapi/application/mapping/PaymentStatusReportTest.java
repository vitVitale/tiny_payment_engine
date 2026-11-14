package com.tpe.paymentapi.application.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.Direction;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.iso20022model.IsoCodec;
import com.tpe.iso20022model.XsdValidator;
import com.tpe.iso20022model.pain002v10.Document;
import com.tpe.iso20022model.pain002v10.PaymentTransaction105;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** specs/10-payment-api.md §3.3: the {@code pain.002.001.10} of an outgoing payment's status. */
class PaymentStatusReportTest {

    private static final MsgId MSG_ID = new MsgId("TPEM01JAQ5V8M9N0P1Q2R3S4T5V6WX");
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:05.250Z");
    private static final TxId TX_ID = new TxId("TPE01JABCDEFGHJKMNPQRSTVW0101");
    private static final String UETR = "eb6305c9-1f7f-49de-aed0-16487c27b42d";

    private final IsoCodec codec = new IsoCodec();
    private final PaymentStatusReport report = new PaymentStatusReport(codec,
            new UlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC), new Random(1)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aSettledPaymentIsAcscWithItsReferences() {
        String xml = report.of(payment(PaymentState.SETTLED, null), MSG_ID, NOW);

        assertThat(new XsdValidator().validate(xml)).isEmpty();
        var document = codec.unmarshal(xml, Document.class).getCstmrPmtStsRpt();
        assertThat(document.getGrpHdr().getMsgId()).isEqualTo(MSG_ID.value());
        assertThat(document.getGrpHdr().getCreDtTm().toString()).isEqualTo("2026-11-14T10:00:05.250Z");
        assertThat(document.getOrgnlGrpInfAndSts().getOrgnlMsgId()).isEqualTo(TX_ID.value());
        assertThat(document.getOrgnlGrpInfAndSts().getOrgnlMsgNmId()).isEqualTo("pain.001.001.09");
        assertThat(document.getOrgnlGrpInfAndSts().getGrpSts()).isEqualTo("ACSC");
        assertThat(document.getOrgnlPmtInfAndSts()).singleElement().satisfies(instruction -> {
            assertThat(instruction.getOrgnlPmtInfId()).isEqualTo(TX_ID.value());
            assertThat(instruction.getTxInfAndSts()).singleElement().satisfies(transaction -> {
                assertThat(transaction.getOrgnlEndToEndId()).isEqualTo("INV-2026-0042");
                assertThat(transaction.getOrgnlUETR()).isEqualTo(UETR);
                assertThat(transaction.getTxSts()).isEqualTo("ACSC");
                assertThat(transaction.getStsRsnInf()).isEmpty();
                assertThat(transaction.getAccptncDtTm().toString()).isEqualTo("2026-11-14T10:00:00.123Z");
            });
        });
    }

    @Test
    void aRejectedPaymentIsRjctWithItsReasonCode() {
        String xml = report.of(payment(PaymentState.REJECTED, ReasonCode.AM04), MSG_ID, NOW);

        assertThat(new XsdValidator().validate(xml)).isEmpty();
        PaymentTransaction105 transaction = transaction(xml);
        assertThat(transaction.getTxSts()).isEqualTo("RJCT");
        assertThat(transaction.getStsRsnInf()).singleElement()
                .satisfies(reason -> assertThat(reason.getRsn().getCd()).isEqualTo("AM04"));
        assertThat(codec.unmarshal(xml, Document.class).getCstmrPmtStsRpt().getOrgnlGrpInfAndSts().getGrpSts())
                .isEqualTo("RJCT");
    }

    @Test
    void eachReportHasItsOwnMsgIdAndTheTimeOfNow() {
        String first = report.of(payment(PaymentState.SETTLED, null));
        String second = report.of(payment(PaymentState.SETTLED, null));

        var header = codec.unmarshal(first, Document.class).getCstmrPmtStsRpt().getGrpHdr();
        assertThat(header.getMsgId()).matches("TPEM[0-9A-HJKMNP-TV-Z]{26}")
                .isNotEqualTo(codec.unmarshal(second, Document.class).getCstmrPmtStsRpt().getGrpHdr().getMsgId());
        assertThat(header.getCreDtTm().toString()).isEqualTo("2026-11-14T10:00:05.250Z");
    }

    /** 00 §6: three fraction digits, whether the instant has none or carries microseconds. */
    @Test
    void timesAreWrittenWithMilliseconds() {
        PaymentView whole = payment(PaymentState.SETTLED, null);
        PaymentView acceptedOnTheSecond = new PaymentView(whole.paymentId(), whole.direction(), whole.onUs(),
                whole.state(), null, whole.txId(), whole.uetr(), whole.endToEndId(), whole.amount(), whole.debtor(),
                whole.creditor(), whole.remittanceInformation(), Instant.parse("2026-11-14T10:00:00Z"),
                whole.finalisedAt());

        String xml = report.of(acceptedOnTheSecond, MSG_ID, Instant.parse("2026-11-14T10:00:05.250123Z"));

        assertThat(xml).contains("<CreDtTm>2026-11-14T10:00:05.250Z</CreDtTm>")
                .contains("<AccptncDtTm>2026-11-14T10:00:00.000Z</AccptncDtTm>");
        assertThat(new XsdValidator().validate(xml)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentState.class, names = {"RECEIVED", "RISK_CHECKED", "FUNDS_RESERVED", "SENT_TO_CSM",
            "INVESTIGATING"})
    void aPaymentNotFinalYetIsPdng(PaymentState state) {
        String xml = report.of(payment(state, null), MSG_ID, NOW);

        assertThat(new XsdValidator().validate(xml)).isEmpty();
        assertThat(transaction(xml).getTxSts()).isEqualTo("PDNG");
    }

    private PaymentTransaction105 transaction(String xml) {
        return codec.unmarshal(xml, Document.class).getCstmrPmtStsRpt().getOrgnlPmtInfAndSts().getFirst()
                .getTxInfAndSts().getFirst();
    }

    static PaymentView payment(PaymentState state, StatusReason reason) {
        return new PaymentView(new PaymentId(UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-000000000101")),
                Direction.OUTGOING, false, state, reason, TX_ID, new Uetr(UETR),
                new EndToEndId("INV-2026-0042"), new Money(12_345, Currency.getInstance("EUR")),
                new PaymentView.Holder(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001")),
                new PaymentView.Holder(new PartyName("Max Mustermann"), new Iban("DE63200200200000000001")),
                new RemittanceInformation("Invoice 42"), Instant.parse("2026-11-14T10:00:00.123Z"),
                state == PaymentState.SETTLED || state == PaymentState.REJECTED
                        ? Instant.parse("2026-11-14T10:00:01.5Z") : null);
    }
}
