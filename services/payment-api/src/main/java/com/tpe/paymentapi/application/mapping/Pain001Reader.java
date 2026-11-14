package com.tpe.paymentapi.application.mapping;

import com.tpe.iso20022model.IsoCodec;
import com.tpe.iso20022model.XsdValidator;
import com.tpe.iso20022model.pain001v09.AccountIdentification4Choice;
import com.tpe.iso20022model.pain001v09.ActiveOrHistoricCurrencyAndAmount;
import com.tpe.iso20022model.pain001v09.BranchAndFinancialInstitutionIdentification6;
import com.tpe.iso20022model.pain001v09.CashAccount38;
import com.tpe.iso20022model.pain001v09.CreditTransferTransaction34;
import com.tpe.iso20022model.pain001v09.CustomerCreditTransferInitiationV09;
import com.tpe.iso20022model.pain001v09.DateAndDateTime2Choice;
import com.tpe.iso20022model.pain001v09.Document;
import com.tpe.iso20022model.pain001v09.PartyIdentification135;
import com.tpe.iso20022model.pain001v09.PaymentInstruction30;
import com.tpe.iso20022model.pain001v09.PaymentTypeInformation26;
import com.tpe.paymentapi.application.validation.PaymentInstruction;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * The ISO 20022 mapper's {@code pain.001} → command half (specs/10-payment-api.md §3.1, §5.2): a {@code pain.001.001.09}
 * is checked against its XSD, then for the variant this API takes, then read into the {@link PaymentInstruction} the
 * equivalent JSON body is, for the same validation. The variant: exactly one {@code PmtInf} with exactly one
 * {@code CdtTrfTxInf}, local instrument {@code INST} (the transaction's {@code PmtTpInf/LclInstrm} if it has one, else
 * the payment information's), {@code ReqdExctnDt} today (UTC, as the interbank settlement date, Q-12) or absent. The
 * date is checked apart, by {@link #requireToday}, so that the idempotency guard answers a retry of an accepted
 * document after midnight with its stored response (UC-01 2a) instead of refusing it.
 *
 * <p>The values read are personal data: neither the exceptions' messages nor the violations they log carry them.
 */
public class Pain001Reader {

    public static final String VERSION = "pain.001.001.09";
    /** ISO 20022's value of an end-to-end id the debtor did not give, and of a message id that cannot be read. */
    public static final String NOT_PROVIDED = "NOTPROVIDED";

    private static final String INSTANT = "INST";
    /** {@code Max35Text}: a longer or empty {@code MsgId} would make the FF01 {@code pain.002} invalid too. */
    private static final int MAX_MESSAGE_ID = 35;

    private final XsdValidator validator;
    private final IsoCodec codec;
    private final Clock clock;

    public Pain001Reader(XsdValidator validator, IsoCodec codec, Clock clock) {
        this.validator = validator;
        this.codec = codec;
        this.clock = clock;
    }

    /**
     * A {@code pain.001} read: its {@code GrpHdr/MsgId}, the payment and its {@code ReqdExctnDt} as a UTC date.
     *
     * @param requestedExecutionDate {@code null} when absent
     */
    public record Pain001(String messageId, PaymentInstruction instruction, LocalDate requestedExecutionDate) {}

    /**
     * @param vopReference the {@code VoP-Reference} header: no {@code pain.001} element carries it
     * @throws UnsupportedVariantException another version, more than one payment or another local instrument
     * @throws SchemaInvalidException not a schema-valid {@code pain.001.001.09}, a DOCTYPE included
     */
    public Pain001 read(String xml, String vopReference) {
        List<XsdValidator.Violation> violations = validator.validate(xml, VERSION);
        if (violations.size() == 1 && violations.getFirst().code().equals(XsdValidator.UNSUPPORTED_NAMESPACE)) {
            throw new UnsupportedVariantException("the document is not a " + VERSION);
        }
        if (!violations.isEmpty()) {
            throw new SchemaInvalidException(readableMessageId(xml, violations), violations);
        }
        CustomerCreditTransferInitiationV09 initiation = codec.unmarshal(xml, Document.class).getCstmrCdtTrfInitn();
        String messageId = initiation.getGrpHdr().getMsgId();
        if (initiation.getPmtInf().size() != 1 || initiation.getPmtInf().getFirst().getCdtTrfTxInf().size() != 1) {
            throw new UnsupportedVariantException("a pain.001 has exactly one PmtInf with one CdtTrfTxInf");
        }
        PaymentInstruction30 information = initiation.getPmtInf().getFirst();
        CreditTransferTransaction34 transaction = information.getCdtTrfTxInf().getFirst();
        requireInstant(localInstrument(transaction.getPmtTpInf(), information.getPmtTpInf()));
        return new Pain001(messageId, instruction(information, transaction, vopReference),
                utcDate(information.getReqdExctnDt()));
    }

    /** @throws UnsupportedVariantException the requested execution date is not today (UTC) */
    public void requireToday(Pain001 document) {
        LocalDate requested = document.requestedExecutionDate();
        if (requested != null && !requested.equals(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))) {
            throw new UnsupportedVariantException("the requested execution date is not today");
        }
    }

    private static PaymentInstruction instruction(PaymentInstruction30 information,
            CreditTransferTransaction34 transaction, String vopReference) {
        String endToEndId = transaction.getPmtId().getEndToEndId();
        ActiveOrHistoricCurrencyAndAmount amount = transaction.getAmt().getInstdAmt();
        List<String> remittance = transaction.getRmtInf() == null ? List.of() : transaction.getRmtInf().getUstrd();
        return new PaymentInstruction(
                endToEndId == null || endToEndId.isBlank() ? NOT_PROVIDED : endToEndId,
                new PaymentInstruction.Debtor(name(information.getDbtr()), iban(information.getDbtrAcct())),
                new PaymentInstruction.Creditor(name(transaction.getCdtr()), iban(transaction.getCdtrAcct()),
                        bic(transaction.getCdtrAgt())),
                amount == null ? null : new PaymentInstruction.Amount(amount.getValue().toPlainString(),
                        amount.getCcy()),
                remittance.isEmpty() ? null : String.join(" ", remittance),
                vopReference);
    }

    /** The transaction's local instrument if it names one, else the payment information's. */
    private static String localInstrument(PaymentTypeInformation26 transaction, PaymentTypeInformation26 information) {
        for (PaymentTypeInformation26 type : new PaymentTypeInformation26[] {transaction, information}) {
            if (type != null && type.getLclInstrm() != null) {
                return type.getLclInstrm().getCd();
            }
        }
        return null;
    }

    private static void requireInstant(String instrument) {
        if (!INSTANT.equals(instrument)) {
            throw new UnsupportedVariantException("the local instrument is not INST");
        }
    }

    /** {@code Dt} as written; {@code DtTm} as its UTC date, or as written when it has no offset. */
    private static LocalDate utcDate(DateAndDateTime2Choice requested) {
        if (requested == null || requested.getDt() == null && requested.getDtTm() == null) {
            return null;
        }
        XMLGregorianCalendar date = requested.getDt() != null ? requested.getDt() : requested.getDtTm();
        if (requested.getDt() == null && date.getTimezone() != DatatypeConstants.FIELD_UNDEFINED) {
            return LocalDate.ofInstant(date.toGregorianCalendar().toInstant(), ZoneOffset.UTC);
        }
        return LocalDate.of(date.getYear(), date.getMonth(), date.getDay());
    }

    /** The {@code MsgId} of a schema-invalid document, if it is well-formed and has one. */
    private String readableMessageId(String xml, List<XsdValidator.Violation> violations) {
        boolean unreadable = violations.stream().map(XsdValidator.Violation::code).anyMatch(code ->
                code.equals(XsdValidator.DOCTYPE_DISALLOWED) || code.equals(XsdValidator.NOT_WELL_FORMED));
        if (unreadable) {
            return NOT_PROVIDED;
        }
        try {
            CustomerCreditTransferInitiationV09 initiation =
                    codec.unmarshal(xml, Document.class).getCstmrCdtTrfInitn();
            String messageId = initiation == null || initiation.getGrpHdr() == null ? null
                    : initiation.getGrpHdr().getMsgId();
            return messageId == null || messageId.isBlank() || messageId.length() > MAX_MESSAGE_ID ? NOT_PROVIDED
                    : messageId;
        } catch (IllegalArgumentException e) {
            return NOT_PROVIDED;
        }
    }

    private static String name(PartyIdentification135 party) {
        return party == null ? null : party.getNm();
    }

    private static String iban(CashAccount38 account) {
        AccountIdentification4Choice id = account == null ? null : account.getId();
        return id == null ? null : id.getIBAN();
    }

    private static String bic(BranchAndFinancialInstitutionIdentification6 agent) {
        return agent == null || agent.getFinInstnId() == null ? null : agent.getFinInstnId().getBICFI();
    }

    /** A {@code pain.001} this API does not take: {@code 400 UNSUPPORTED_MEDIA_TYPE_VARIANT}. */
    public static final class UnsupportedVariantException extends RuntimeException {

        UnsupportedVariantException(String message) {
            super(message);
        }
    }

    /**
     * Not a schema-valid {@code pain.001.001.09}: {@code 400 VALIDATION_FAILED} with a {@code pain.002 RJCT FF01}.
     *
     * @param messageId of the rejected document, {@link #NOT_PROVIDED} when it cannot be read
     */
    public static final class SchemaInvalidException extends RuntimeException {

        private final String messageId;
        private final List<XsdValidator.Violation> violations;

        SchemaInvalidException(String messageId, List<XsdValidator.Violation> violations) {
            super("the pain.001 is not schema-valid: " + violations.stream()
                    .map(v -> v.code() + " at " + v.line() + ":" + v.column()).toList());
            this.messageId = messageId;
            this.violations = List.copyOf(violations);
        }

        public String messageId() {
            return messageId;
        }

        /** Their messages may quote the document: log the codes and positions only. */
        public List<XsdValidator.Violation> violations() {
            return violations;
        }
    }
}
