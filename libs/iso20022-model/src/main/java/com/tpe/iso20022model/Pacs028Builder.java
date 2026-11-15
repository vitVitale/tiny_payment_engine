package com.tpe.iso20022model;

import static com.tpe.iso20022model.CsmDocuments.dateTime;

import com.tpe.iso20022model.pacs028v03.BranchAndFinancialInstitutionIdentification6;
import com.tpe.iso20022model.pacs028v03.Document;
import com.tpe.iso20022model.pacs028v03.FIToFIPaymentStatusRequestV03;
import com.tpe.iso20022model.pacs028v03.FinancialInstitutionIdentification18;
import com.tpe.iso20022model.pacs028v03.GroupHeader91;
import com.tpe.iso20022model.pacs028v03.OriginalGroupInformation29;
import com.tpe.iso20022model.pacs028v03.OriginalTransactionReference28;
import com.tpe.iso20022model.pacs028v03.PaymentTransaction113;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.util.Objects;

/**
 * Builds the {@code pacs.028.001.03} status request for one {@code pacs.008} transaction in its CSM envelope
 * (specs/01-shared-libraries.md §2.2). The request's {@code MsgId} is also its {@code StsReqId}; it carries
 * {@code OrgnlTxRef/DbtrAgt} so the transaction is identified by (debtor agent BIC, {@code TxId}) (ADR-010). The
 * header goes from the instructing agent to the instructed agent. Stateless and thread-safe.
 */
public final class Pacs028Builder {

    /**
     * One status request.
     *
     * @param msgId the request's {@code MsgId} and {@code StsReqId}, also the header's {@code BizMsgIdr}
     * @param createdAt its {@code CreDtTm}
     * @param originalMsgId the {@code MsgId} of the {@code pacs.008} asked about
     * @param debtorAgent that transaction's {@code DbtrAgt} BIC: ours for an outgoing transfer
     * @param instructingAgent the agent sending this request
     * @param instructedAgent the agent receiving it
     */
    public record StatusRequest(
            MsgId msgId,
            Instant createdAt,
            MsgId originalMsgId,
            TxId originalTxId,
            EndToEndId originalEndToEndId,
            Uetr originalUetr,
            Bic debtorAgent,
            Bic instructingAgent,
            Bic instructedAgent) {

        public StatusRequest {
            Objects.requireNonNull(msgId, "msgId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(originalMsgId, "originalMsgId");
            Objects.requireNonNull(originalTxId, "originalTxId");
            Objects.requireNonNull(originalEndToEndId, "originalEndToEndId");
            Objects.requireNonNull(originalUetr, "originalUetr");
            Objects.requireNonNull(debtorAgent, "debtorAgent");
            Objects.requireNonNull(instructingAgent, "instructingAgent");
            Objects.requireNonNull(instructedAgent, "instructedAgent");
        }
    }

    public CsmEnvelope build(StatusRequest request) {
        GroupHeader91 group = new GroupHeader91();
        group.setMsgId(request.msgId().value());
        group.setCreDtTm(dateTime(request.createdAt()));

        OriginalGroupInformation29 original = new OriginalGroupInformation29();
        original.setOrgnlMsgId(request.originalMsgId().value());
        original.setOrgnlMsgNmId(CsmMessageType.PACS_008.version());

        PaymentTransaction113 transaction = new PaymentTransaction113();
        transaction.setStsReqId(request.msgId().value());
        transaction.setOrgnlGrpInf(original);
        transaction.setOrgnlEndToEndId(request.originalEndToEndId().value());
        transaction.setOrgnlTxId(request.originalTxId().value());
        transaction.setOrgnlUETR(request.originalUetr().value());
        transaction.setInstgAgt(agent(request.instructingAgent()));
        transaction.setInstdAgt(agent(request.instructedAgent()));
        OriginalTransactionReference28 reference = new OriginalTransactionReference28();
        reference.setDbtrAgt(agent(request.debtorAgent()));
        transaction.setOrgnlTxRef(reference);

        FIToFIPaymentStatusRequestV03 message = new FIToFIPaymentStatusRequestV03();
        message.setGrpHdr(group);
        message.getTxInf().add(transaction);
        Document document = new Document();
        document.setFIToFIPmtStsReq(message);
        return new CsmEnvelope(
                CsmDocuments.header(request.instructingAgent(), request.instructedAgent(), request.msgId(),
                        CsmMessageType.PACS_028, request.createdAt()),
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
