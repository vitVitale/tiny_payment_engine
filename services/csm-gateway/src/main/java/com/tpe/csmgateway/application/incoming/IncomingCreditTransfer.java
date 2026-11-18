package com.tpe.csmgateway.application.incoming;

import com.tpe.contracts.CreditTransferReceived;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Party;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.pacs008v08.BranchAndFinancialInstitutionIdentification6;
import com.tpe.iso20022model.pacs008v08.CreditTransferTransaction39;
import com.tpe.iso20022model.pacs008v08.FIToFICustomerCreditTransferV08;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A schema-valid incoming {@code pacs.008} read into domain types and checked as the gateway takes it
 * (specs/15-csm-gateway.md §6.2): {@code InstdAgt} (if given) and {@code CdtrAgt} our BIC, {@code IntrBkSttlmCcy =
 * EUR}, {@code NbOfTxs = 1}, every value a valid domain value. One that fails is answered {@code RJCT FF01}.
 */
public record IncomingCreditTransfer(
        IncomingIdentity identity,
        Instant acceptanceDateTime,
        LocalDate interbankSettlementDate,
        Money amount,
        Party debtor,
        Party creditor,
        Bic creditorAgent,
        RemittanceInformation remittanceInformation) {

    /** The violation codes the gateway's own checks journal (§6.2, step 1). */
    static final String NOT_ONE_TRANSACTION = "NOT_ONE_TRANSACTION";
    static final String NOT_OUR_CREDITOR_AGENT = "NOT_OUR_CREDITOR_AGENT";
    static final String NOT_OUR_INSTRUCTED_AGENT = "NOT_OUR_INSTRUCTED_AGENT";
    static final String NOT_EUR = "NOT_EUR";
    static final String UNREADABLE_IDENTITY = "UNREADABLE_IDENTITY";
    static final String NO_UETR = "NO_UETR";
    static final String UNREADABLE_VALUE = "UNREADABLE_VALUE";

    private static final String EUR = "EUR";
    private static final String ONE = "1";

    /** The outcome of {@link #read}. */
    public sealed interface Reading permits Taken, Refused {}

    /** Valid: a new one becomes a payment. */
    public record Taken(IncomingCreditTransfer transfer) implements Reading {}

    /** Invalid, with the codes of what failed; answered {@code RJCT FF01} when {@code identity} is known. */
    public record Refused(Optional<IncomingIdentity> identity, List<String> violations) implements Reading {}

    public IncomingCreditTransfer {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(identity.msgId(), "msgId");
        Objects.requireNonNull(identity.endToEndId(), "endToEndId");
        Objects.requireNonNull(identity.uetr(), "uetr");
        Objects.requireNonNull(acceptanceDateTime, "acceptanceDateTime");
        Objects.requireNonNull(interbankSettlementDate, "interbankSettlementDate");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(debtor, "debtor");
        Objects.requireNonNull(creditor, "creditor");
        Objects.requireNonNull(creditorAgent, "creditorAgent");
    }

    /** Reads a schema-valid {@code pacs.008} sent to {@code ownBic}. */
    public static Reading read(CsmEnvelope envelope, Bic ownBic) {
        var message = ((com.tpe.iso20022model.pacs008v08.Document) envelope.document()).getFIToFICstmrCdtTrf();
        CreditTransferTransaction39 transaction = message.getCdtTrfTxInf().getFirst();
        Optional<IncomingIdentity> identity = identity(message, transaction);
        List<String> violations = new ArrayList<>();
        if (!ONE.equals(message.getGrpHdr().getNbOfTxs()) || message.getCdtTrfTxInf().size() != 1) {
            violations.add(NOT_ONE_TRANSACTION);
        }
        if (!ownBic.value().equals(bic(transaction.getCdtrAgt()))) {
            violations.add(NOT_OUR_CREDITOR_AGENT);
        }
        if (!instructedTo(message.getGrpHdr().getInstdAgt(), ownBic)
                || !instructedTo(transaction.getInstdAgt(), ownBic)) {
            violations.add(NOT_OUR_INSTRUCTED_AGENT);
        }
        if (!EUR.equals(transaction.getIntrBkSttlmAmt().getCcy())) {
            violations.add(NOT_EUR);
        }
        if (identity.isEmpty()) {
            violations.add(UNREADABLE_IDENTITY);
        } else if (identity.get().uetr() == null) {
            violations.add(NO_UETR);
        }
        if (!violations.isEmpty()) {
            return new Refused(identity, violations);
        }
        try {
            return new Taken(new IncomingCreditTransfer(identity.get(),
                    transaction.getAccptncDtTm().toGregorianCalendar().toInstant(),
                    LocalDate.parse(message.getGrpHdr().getIntrBkSttlmDt().toXMLFormat()),
                    Money.parse(transaction.getIntrBkSttlmAmt().getValue().toPlainString()),
                    new Party(new PartyName(transaction.getDbtr().getNm()),
                            new Iban(transaction.getDbtrAcct().getId().getIBAN()), null),
                    new Party(new PartyName(transaction.getCdtr().getNm()),
                            new Iban(transaction.getCdtrAcct().getId().getIBAN()), null),
                    new Bic(bic(transaction.getCdtrAgt())),
                    transaction.getRmtInf() == null ? null : transaction.getRmtInf().getUstrd().stream()
                            .findFirst().map(RemittanceInformation::new).orElse(null)));
        } catch (DomainValidationException e) {
            return new Refused(identity, List.of(e.code().name()));
        } catch (RuntimeException e) {
            // a value the XSD lets through but a payment cannot carry (e.g. a debtor without Nm or IBAN)
            return new Refused(identity, List.of(UNREADABLE_VALUE));
        }
    }

    /** The event for the orchestrator (specs/02-messaging-contracts.md §4). */
    public CreditTransferReceived received(Instant receivedAt, Instant answerDeadline) {
        return new CreditTransferReceived(identity.txId(), identity.debtorAgent(), Direction.INCOMING,
                identity.msgId(), identity.uetr(), identity.endToEndId(), receivedAt, answerDeadline,
                acceptanceDateTime, interbankSettlementDate, amount, debtor, creditor, creditorAgent,
                remittanceInformation);
    }

    private static Optional<IncomingIdentity> identity(FIToFICustomerCreditTransferV08 message,
            CreditTransferTransaction39 transaction) {
        try {
            var payment = transaction.getPmtId();
            return Optional.of(new IncomingIdentity(new TxId(payment.getTxId()),
                    new Bic(bic(transaction.getDbtrAgt())), new MsgId(message.getGrpHdr().getMsgId()),
                    new EndToEndId(payment.getEndToEndId()),
                    payment.getUETR() == null ? null : new Uetr(payment.getUETR())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean instructedTo(BranchAndFinancialInstitutionIdentification6 agent, Bic ownBic) {
        return agent == null || ownBic.value().equals(bic(agent));
    }

    private static String bic(BranchAndFinancialInstitutionIdentification6 agent) {
        return agent == null || agent.getFinInstnId() == null ? null : agent.getFinInstnId().getBICFI();
    }
}
