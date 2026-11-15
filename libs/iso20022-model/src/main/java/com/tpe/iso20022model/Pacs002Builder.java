package com.tpe.iso20022model;

import static com.tpe.iso20022model.CsmDocuments.dateTime;

import com.tpe.iso20022model.pacs002v10.BranchAndFinancialInstitutionIdentification6;
import com.tpe.iso20022model.pacs002v10.Document;
import com.tpe.iso20022model.pacs002v10.FIToFIPaymentStatusReportV10;
import com.tpe.iso20022model.pacs002v10.FinancialInstitutionIdentification18;
import com.tpe.iso20022model.pacs002v10.GroupHeader91;
import com.tpe.iso20022model.pacs002v10.OriginalGroupHeader17;
import com.tpe.iso20022model.pacs002v10.OriginalTransactionReference28;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.iso20022model.pacs002v10.StatusReason6Choice;
import com.tpe.iso20022model.pacs002v10.StatusReasonInformation12;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.util.Objects;

/**
 * Builds the {@code pacs.002.001.10} status report of one transaction in its CSM envelope
 * (specs/01-shared-libraries.md §2.2). It carries {@code OrgnlTxRef/DbtrAgt} so the receiver can correlate by
 * (debtor agent BIC, {@code TxId}) (ADR-010). The header goes from the instructing agent to the instructed agent.
 * Which status to report is the caller's decision. Stateless and thread-safe.
 */
public final class Pacs002Builder {

    /** The {@code TxSts} and, for a rejection, the {@code StsRsnInf/Rsn}. */
    public static final class Status {

        /**
         * The proprietary reason of a {@code RJCT} answering a {@code pacs.028} for a transaction the CSM never
         * received (specs/15-csm-gateway.md §6.4, specs/17-csm-simulator.md).
         */
        public static final String UNKNOWN_TX_REASON = "UNKNOWN_TX";

        /** {@code ACCP}: the creditor agent accepted; settlement is pending. */
        public static final Status ACCEPTED = new Status("ACCP", null, null);
        /** {@code ACSC}: settled. */
        public static final Status SETTLED = new Status("ACSC", null, null);
        /** {@code RJCT} with {@code Prtry = UNKNOWN_TX}. */
        public static final Status UNKNOWN_TRANSACTION = new Status("RJCT", null, UNKNOWN_TX_REASON);

        private final String code;
        private final String reasonCode;
        private final String proprietaryReason;

        private Status(String code, String reasonCode, String proprietaryReason) {
            this.code = code;
            this.reasonCode = reasonCode;
            this.proprietaryReason = proprietaryReason;
        }

        /** {@code RJCT} with {@code Cd = reason}. */
        public static Status rejected(StatusReason reason) {
            return new Status("RJCT", Objects.requireNonNull(reason, "reason").code(), null);
        }

        /** The {@code TxSts}: {@code ACCP}, {@code ACSC} or {@code RJCT}. */
        public String code() {
            return code;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Status other && code.equals(other.code)
                    && Objects.equals(reasonCode, other.reasonCode)
                    && Objects.equals(proprietaryReason, other.proprietaryReason);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, reasonCode, proprietaryReason);
        }

        @Override
        public String toString() {
            String reason = reasonCode != null ? reasonCode : proprietaryReason;
            return reason == null ? code : code + "(" + reason + ")";
        }
    }

    /**
     * One status report.
     *
     * @param msgId the report's {@code MsgId}, also the header's {@code BizMsgIdr}
     * @param createdAt its {@code CreDtTm}
     * @param originalMsgId the {@code MsgId} of the message it answers; {@code NOTPROVIDED} when it could not be read
     * @param originalMessageType that message's type: {@code pacs.008}, or {@code pacs.028} for an investigation
     * @param originalEndToEndId {@code null} when the original could not be read (a format rejection)
     * @param originalUetr {@code null} when the original could not be read
     * @param debtorAgent the original transaction's {@code DbtrAgt} BIC, the {@code OrgnlTxRef/DbtrAgt};
     *     {@code null} when it could not be read
     * @param instructingAgent the agent sending this report
     * @param instructedAgent the agent receiving it
     */
    public record StatusReport(
            MsgId msgId,
            Instant createdAt,
            MsgId originalMsgId,
            CsmMessageType originalMessageType,
            TxId originalTxId,
            EndToEndId originalEndToEndId,
            Uetr originalUetr,
            Bic debtorAgent,
            Status status,
            Bic instructingAgent,
            Bic instructedAgent) {

        public StatusReport {
            Objects.requireNonNull(msgId, "msgId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(originalMsgId, "originalMsgId");
            Objects.requireNonNull(originalMessageType, "originalMessageType");
            Objects.requireNonNull(originalTxId, "originalTxId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(instructingAgent, "instructingAgent");
            Objects.requireNonNull(instructedAgent, "instructedAgent");
        }
    }

    public CsmEnvelope build(StatusReport report) {
        GroupHeader91 group = new GroupHeader91();
        group.setMsgId(report.msgId().value());
        group.setCreDtTm(dateTime(report.createdAt()));

        OriginalGroupHeader17 original = new OriginalGroupHeader17();
        original.setOrgnlMsgId(report.originalMsgId().value());
        original.setOrgnlMsgNmId(report.originalMessageType().version());

        PaymentTransaction110 transaction = new PaymentTransaction110();
        if (report.originalEndToEndId() != null) {
            transaction.setOrgnlEndToEndId(report.originalEndToEndId().value());
        }
        transaction.setOrgnlTxId(report.originalTxId().value());
        if (report.originalUetr() != null) {
            transaction.setOrgnlUETR(report.originalUetr().value());
        }
        Status status = report.status();
        transaction.setTxSts(status.code);
        if (status.reasonCode != null || status.proprietaryReason != null) {
            StatusReason6Choice reason = new StatusReason6Choice();
            reason.setCd(status.reasonCode);
            reason.setPrtry(status.proprietaryReason);
            StatusReasonInformation12 information = new StatusReasonInformation12();
            information.setRsn(reason);
            transaction.getStsRsnInf().add(information);
        }
        transaction.setInstgAgt(agent(report.instructingAgent()));
        transaction.setInstdAgt(agent(report.instructedAgent()));
        if (report.debtorAgent() != null) {
            OriginalTransactionReference28 reference = new OriginalTransactionReference28();
            reference.setDbtrAgt(agent(report.debtorAgent()));
            transaction.setOrgnlTxRef(reference);
        }

        FIToFIPaymentStatusReportV10 message = new FIToFIPaymentStatusReportV10();
        message.setGrpHdr(group);
        message.getOrgnlGrpInfAndSts().add(original);
        message.getTxInfAndSts().add(transaction);
        Document document = new Document();
        document.setFIToFIPmtStsRpt(message);
        return new CsmEnvelope(
                CsmDocuments.header(report.instructingAgent(), report.instructedAgent(), report.msgId(),
                        CsmMessageType.PACS_002, report.createdAt()),
                document);
    }

    private static BranchAndFinancialInstitutionIdentification6 agent(Bic bic) {
        FinancialInstitutionIdentification18 institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(bic.value());
        BranchAndFinancialInstitutionIdentification6 agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(institution);
        return agent;
    }
}
