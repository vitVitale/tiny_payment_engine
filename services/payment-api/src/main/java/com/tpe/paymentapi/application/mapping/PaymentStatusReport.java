package com.tpe.paymentapi.application.mapping;

import com.tpe.contracts.payment.PaymentView;
import com.tpe.iso20022model.IsoCodec;
import com.tpe.iso20022model.pain002v10.CustomerPaymentStatusReportV10;
import com.tpe.iso20022model.pain002v10.Document;
import com.tpe.iso20022model.pain002v10.GroupHeader86;
import com.tpe.iso20022model.pain002v10.OriginalGroupHeader17;
import com.tpe.iso20022model.pain002v10.OriginalPaymentInstruction32;
import com.tpe.iso20022model.pain002v10.PaymentTransaction105;
import com.tpe.iso20022model.pain002v10.StatusReason6Choice;
import com.tpe.iso20022model.pain002v10.StatusReasonInformation12;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * The {@code pain.002.001.10} of an outgoing payment (specs/10-payment-api.md §3.3, UC-07): {@code GrpSts} and
 * {@code TxSts} as {@link CustomerStatus} maps the state, {@code OrgnlEndToEndId}, {@code OrgnlUETR},
 * {@code AccptncDtTm} and, when rejected, {@code StsRsnInf/Rsn/Cd}. A payment initiated as JSON has no original
 * message or payment information id the orchestrator keeps, so both are the payment's {@code TxId}, the reference
 * the bank gave it.
 */
public class PaymentStatusReport {

    static final String ORIGINAL_MESSAGE = "pain.001.001.09";
    private static final DateTimeFormatter MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final IsoCodec codec;
    private final UlidGenerator ulids;
    private final Clock clock;
    private final DatatypeFactory datatypes = DatatypeFactory.newDefaultInstance();

    public PaymentStatusReport(IsoCodec codec, UlidGenerator ulids, Clock clock) {
        this.codec = codec;
        this.ulids = ulids;
        this.clock = clock;
    }

    /**
     * The report as of now, with a new {@code MsgId}.
     *
     * @throws IllegalArgumentException for an incoming payment's state, which this API never shows
     */
    public String of(PaymentView payment) {
        return of(payment, MsgId.generate(ulids), clock.instant());
    }

    /**
     * @param msgId the report's own {@code MsgId}
     * @param createdAt its {@code CreDtTm}
     */
    String of(PaymentView payment, MsgId msgId, Instant createdAt) {
        String status = CustomerStatus.of(payment.state()).name();

        GroupHeader86 header = new GroupHeader86();
        header.setMsgId(msgId.value());
        header.setCreDtTm(dateTime(createdAt));

        OriginalGroupHeader17 group = new OriginalGroupHeader17();
        group.setOrgnlMsgId(payment.txId().value());
        group.setOrgnlMsgNmId(ORIGINAL_MESSAGE);
        group.setGrpSts(status);

        PaymentTransaction105 transaction = new PaymentTransaction105();
        transaction.setOrgnlEndToEndId(payment.endToEndId().value());
        transaction.setOrgnlUETR(payment.uetr().value());
        transaction.setTxSts(status);
        if (payment.reasonCode() != null) {
            StatusReason6Choice reason = new StatusReason6Choice();
            reason.setCd(payment.reasonCode().code());
            StatusReasonInformation12 information = new StatusReasonInformation12();
            information.setRsn(reason);
            transaction.getStsRsnInf().add(information);
        }
        transaction.setAccptncDtTm(dateTime(payment.acceptedAt()));

        OriginalPaymentInstruction32 instruction = new OriginalPaymentInstruction32();
        instruction.setOrgnlPmtInfId(payment.txId().value());
        instruction.getTxInfAndSts().add(transaction);

        CustomerPaymentStatusReportV10 report = new CustomerPaymentStatusReportV10();
        report.setGrpHdr(header);
        report.setOrgnlGrpInfAndSts(group);
        report.getOrgnlPmtInfAndSts().add(instruction);
        Document document = new Document();
        document.setCstmrPmtStsRpt(report);
        return codec.marshal(document);
    }

    /** UTC with exactly three fraction digits (specs/00-engineering-baseline.md §6), as the golden files have it. */
    private XMLGregorianCalendar dateTime(Instant instant) {
        return datatypes.newXMLGregorianCalendar(MILLIS.format(instant));
    }
}
