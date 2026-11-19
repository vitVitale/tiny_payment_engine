package com.tpe.systemtests.harness;

import com.tpe.invariantchecker.CsmInvariants;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.iso20022model.pacs002v10.BranchAndFinancialInstitutionIdentification6;
import com.tpe.iso20022model.pacs002v10.Document;
import com.tpe.iso20022model.pacs002v10.OriginalGroupHeader17;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;

/**
 * Status reports a test sends to csm-gateway as if the CSM had sent them, through the simulator's raw endpoint
 * (specs/17-csm-simulator.md §3.5): E2E-O-14's late {@code pacs.002} that contradicts the CSM's first answer; and
 * the reading of our answers to incoming transfers in the CSM's received log (INV-6).
 */
public final class StatusReports {

    private static final EnvelopeCodec ENVELOPES = new EnvelopeCodec();
    private static final UlidGenerator ULIDS = new UlidGenerator(Clock.systemUTC(), new Random());

    private StatusReports() {}

    /**
     * A status report of the same transaction as {@code report} (a {@code pacs.002} envelope the CSM sent), from the
     * same agent to the same agent, answering the same original message, but with {@code status} under a new
     * {@code MsgId}: not a duplicate of the first report, a second answer.
     *
     * @throws IllegalArgumentException if {@code report} is not a valid {@code pacs.002} envelope
     */
    public static String contradicting(String report, Status status) {
        if (!(ENVELOPES.split(report) instanceof Accepted accepted)
                || !(accepted.envelope().document() instanceof Document document)) {
            throw new IllegalArgumentException("not a pacs.002 envelope: " + report);
        }
        OriginalGroupHeader17 original = document.getFIToFIPmtStsRpt().getOrgnlGrpInfAndSts().getFirst();
        PaymentTransaction110 transaction = document.getFIToFIPmtStsRpt().getTxInfAndSts().getFirst();
        CsmMessageType originalType = Arrays.stream(CsmMessageType.values())
                .filter(type -> type.version().equals(original.getOrgnlMsgNmId())).findFirst().orElseThrow();
        return ENVELOPES.join(new Pacs002Builder().build(new StatusReport(
                new MsgId("SYSTEST" + ULIDS.next()),
                Instant.now(),
                new MsgId(original.getOrgnlMsgId()),
                originalType,
                new TxId(transaction.getOrgnlTxId()),
                transaction.getOrgnlEndToEndId() == null ? null : new EndToEndId(transaction.getOrgnlEndToEndId()),
                transaction.getOrgnlUETR() == null ? null : new Uetr(transaction.getOrgnlUETR()),
                transaction.getOrgnlTxRef() == null ? null : bic(transaction.getOrgnlTxRef().getDbtrAgt()),
                status,
                bic(transaction.getInstgAgt()),
                bic(transaction.getInstdAgt()))));
    }

    /**
     * What an answer {@code pacs.002} of ours says (15 §6.3), as INV-6 reads the CSM's received log: the transaction
     * ({@code OrgnlTxRef/DbtrAgt}, {@code OrgnlTxId}), the answer's {@code MsgId}, its status and reason.
     *
     * @throws IllegalArgumentException if {@code envelope} is not a valid {@code pacs.002} envelope
     */
    public static CsmInvariants.Answer answer(String envelope) {
        if (!(ENVELOPES.split(envelope) instanceof Accepted accepted)
                || !(accepted.envelope().document() instanceof Document document)) {
            throw new IllegalArgumentException("not a pacs.002 envelope: " + envelope);
        }
        PaymentTransaction110 transaction = document.getFIToFIPmtStsRpt().getTxInfAndSts().getFirst();
        String reason = transaction.getStsRsnInf().isEmpty() || transaction.getStsRsnInf().getFirst().getRsn() == null
                ? null : transaction.getStsRsnInf().getFirst().getRsn().getCd();
        return new CsmInvariants.Answer(bic(transaction.getOrgnlTxRef().getDbtrAgt()).value(),
                transaction.getOrgnlTxId(), document.getFIToFIPmtStsRpt().getGrpHdr().getMsgId(),
                transaction.getTxSts(), reason);
    }

    private static Bic bic(BranchAndFinancialInstitutionIdentification6 agent) {
        return new Bic(agent.getFinInstnId().getBICFI());
    }
}
