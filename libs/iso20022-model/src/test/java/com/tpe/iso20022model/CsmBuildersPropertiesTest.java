package com.tpe.iso20022model;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.iso20022model.EnvelopeCodec.Accepted;
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
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Builders;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * ISO-AC-01 for the CSM builders (specs/01-shared-libraries.md §2.2): every {@code pacs.008}, {@code pacs.002} and
 * {@code pacs.028} built from generated domain inputs validates against its XSD, and so does its header. The id stays
 * pending until every builder of §2.2 exists, so no test name carries it.
 */
class CsmBuildersPropertiesTest {

    private static final String SEPA_CHARACTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/?:().,'+ -";
    /** BBAN lengths (IBAN length − 4) of a few SEPA countries whose BBAN may be all digits. */
    private static final Map<String, Integer> BBAN_LENGTHS = Map.of("DE", 18, "AT", 16, "BE", 12, "FR", 23, "IT", 23);
    private static final long MIN_EPOCH_SECOND = Instant.parse("2000-01-01T00:00:00Z").getEpochSecond();
    private static final long MAX_EPOCH_SECOND = Instant.parse("2099-12-31T23:59:59Z").getEpochSecond();

    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final XsdValidator validator = new XsdValidator();
    private final IsoCodec iso = new IsoCodec();

    @Property
    void everyBuiltCreditTransferValidatesAgainstItsXsd(@ForAll("creditTransfers") CreditTransfer transfer) {
        assertValid(new Pacs008Builder().build(transfer));
    }

    @Property
    void everyBuiltStatusReportValidatesAgainstItsXsd(@ForAll("statusReports") StatusReport report) {
        assertValid(new Pacs002Builder().build(report));
    }

    @Property
    void everyBuiltStatusRequestValidatesAgainstItsXsd(@ForAll("statusRequests") StatusRequest request) {
        assertValid(new Pacs028Builder().build(request));
    }

    private void assertValid(CsmEnvelope built) {
        assertThat(validator.validate(iso.marshal(built.document()))).isEmpty();
        // join validates the header and the document and throws on an invalid part
        assertThat(envelopes.split(envelopes.join(built))).isEqualTo(new Accepted(built));
    }

    @Provide
    Arbitrary<CreditTransfer> creditTransfers() {
        return Builders.withBuilder(CreditTransferInputs::new)
                .use(msgIds()).inSetter((b, v) -> b.msgId = v)
                .use(instants()).inSetter((b, v) -> b.createdAt = v)
                .use(text(35).map(TxId::new)).inSetter((b, v) -> b.txId = v)
                .use(text(35).map(EndToEndId::new)).inSetter((b, v) -> b.endToEndId = v)
                .use(uetrs()).inSetter((b, v) -> b.uetr = v)
                .use(Arbitraries.longs().between(1, 99_999_999_999_999L)).inSetter((b, v) -> b.amount = v)
                .use(dates()).inSetter((b, v) -> b.settlementDate = v)
                .use(instants()).inSetter((b, v) -> b.acceptedAt = v)
                .use(accountHolders()).inSetter((b, v) -> b.debtor = v)
                .use(bics()).inSetter((b, v) -> b.debtorAgent = v)
                .use(bics()).inSetter((b, v) -> b.creditorAgent = v)
                .use(accountHolders()).inSetter((b, v) -> b.creditor = v)
                .use(text(140).map(RemittanceInformation::new).injectNull(0.3)).inSetter((b, v) -> b.remittance = v)
                .build(CreditTransferInputs::build);
    }

    @Provide
    Arbitrary<StatusReport> statusReports() {
        Arbitrary<Status> statuses = Arbitraries.oneOf(
                Arbitraries.of(Status.ACCEPTED, Status.SETTLED, Status.UNKNOWN_TRANSACTION),
                Arbitraries.of(ReasonCode.class).map(Status::rejected),
                // codes this design does not know, as a CSM may relay them
                Arbitraries.strings().withCharRange('A', 'Z').withCharRange('0', '9').ofLength(4)
                        .map(ReasonCode::parse).map(Status::rejected));
        return Builders.withBuilder(StatusReportInputs::new)
                .use(msgIds()).inSetter((b, v) -> b.msgId = v)
                .use(instants()).inSetter((b, v) -> b.createdAt = v)
                .use(msgIds()).inSetter((b, v) -> b.originalMsgId = v)
                .use(Arbitraries.of(CsmMessageType.PACS_008, CsmMessageType.PACS_028)).inSetter((b, v) -> b.originalType = v)
                .use(text(35).map(TxId::new)).inSetter((b, v) -> b.originalTxId = v)
                .use(text(35).map(EndToEndId::new).injectNull(0.2)).inSetter((b, v) -> b.originalEndToEndId = v)
                .use(uetrs().injectNull(0.2)).inSetter((b, v) -> b.originalUetr = v)
                .use(bics().injectNull(0.2)).inSetter((b, v) -> b.debtorAgent = v)
                .use(statuses).inSetter((b, v) -> b.status = v)
                .use(bics()).inSetter((b, v) -> b.instructingAgent = v)
                .use(bics()).inSetter((b, v) -> b.instructedAgent = v)
                .build(StatusReportInputs::build);
    }

    @Provide
    Arbitrary<StatusRequest> statusRequests() {
        return Builders.withBuilder(StatusRequestInputs::new)
                .use(msgIds()).inSetter((b, v) -> b.msgId = v)
                .use(instants()).inSetter((b, v) -> b.createdAt = v)
                .use(msgIds()).inSetter((b, v) -> b.originalMsgId = v)
                .use(text(35).map(TxId::new)).inSetter((b, v) -> b.originalTxId = v)
                .use(text(35).map(EndToEndId::new)).inSetter((b, v) -> b.originalEndToEndId = v)
                .use(uetrs()).inSetter((b, v) -> b.originalUetr = v)
                .use(bics()).inSetter((b, v) -> b.debtorAgent = v)
                .use(bics()).inSetter((b, v) -> b.instructingAgent = v)
                .use(bics()).inSetter((b, v) -> b.instructedAgent = v)
                .build(StatusRequestInputs::build);
    }

    private static Arbitrary<String> text(int maxLength) {
        return Arbitraries.strings().withChars(SEPA_CHARACTERS).ofMinLength(1).ofMaxLength(maxLength)
                .filter(s -> !s.isBlank());
    }

    private static Arbitrary<MsgId> msgIds() {
        return text(35).map(MsgId::new);
    }

    private static Arbitrary<Instant> instants() {
        return Combinators.combine(
                        Arbitraries.longs().between(MIN_EPOCH_SECOND, MAX_EPOCH_SECOND),
                        Arbitraries.integers().between(0, 999_999_999))
                .as(Instant::ofEpochSecond);
    }

    private static Arbitrary<LocalDate> dates() {
        return instants().map(instant -> LocalDate.ofInstant(instant, java.time.ZoneOffset.UTC));
    }

    private static Arbitrary<Uetr> uetrs() {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as((most, least) -> new Uetr(new UUID(
                        (most & ~0xF000L) | 0x4000L, (least & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L)
                .toString()));
    }

    private static Arbitrary<Bic> bics() {
        net.jqwik.api.arbitraries.StringArbitrary letters = Arbitraries.strings().withCharRange('A', 'Z');
        net.jqwik.api.arbitraries.StringArbitrary alphanumerics = Arbitraries.strings().withCharRange('A', 'Z').withCharRange('0', '9');
        return Combinators.combine(
                        letters.ofLength(4),
                        Arbitraries.of("DE", "FR", "AT", "NL", "IT"),
                        alphanumerics.ofLength(2),
                        Arbitraries.oneOf(Arbitraries.just(""), alphanumerics.ofLength(3)))
                .as((bank, country, location, branch) -> new Bic(bank + country + location + branch));
    }

    private static Arbitrary<AccountHolder> accountHolders() {
        return Combinators.combine(text(70).map(PartyName::new), ibans()).as(AccountHolder::new);
    }

    private static Arbitrary<Iban> ibans() {
        return Arbitraries.of(BBAN_LENGTHS.keySet()).flatMap(country -> Arbitraries.strings()
                .withCharRange('0', '9')
                .ofLength(BBAN_LENGTHS.get(country))
                .map(bban -> new Iban(withCheckDigits(country, bban))));
    }

    private static String withCheckDigits(String country, String bban) {
        StringBuilder numeric = new StringBuilder();
        for (char c : (bban + country + "00").toCharArray()) {
            numeric.append(Character.getNumericValue(c));
        }
        int check = 98 - new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
        return country + String.format(Locale.ROOT, "%02d", check) + bban;
    }

    private static final class CreditTransferInputs {
        MsgId msgId;
        Instant createdAt;
        TxId txId;
        EndToEndId endToEndId;
        Uetr uetr;
        long amount;
        LocalDate settlementDate;
        Instant acceptedAt;
        AccountHolder debtor;
        Bic debtorAgent;
        Bic creditorAgent;
        AccountHolder creditor;
        RemittanceInformation remittance;

        CreditTransfer build() {
            return new CreditTransfer(msgId, createdAt, txId, endToEndId, uetr,
                    new Money(amount, Currency.getInstance("EUR")), settlementDate, acceptedAt, debtor, debtorAgent,
                    creditorAgent, creditor, remittance);
        }
    }

    private static final class StatusReportInputs {
        MsgId msgId;
        Instant createdAt;
        MsgId originalMsgId;
        CsmMessageType originalType;
        TxId originalTxId;
        EndToEndId originalEndToEndId;
        Uetr originalUetr;
        Bic debtorAgent;
        Status status;
        Bic instructingAgent;
        Bic instructedAgent;

        StatusReport build() {
            return new StatusReport(msgId, createdAt, originalMsgId, originalType, originalTxId, originalEndToEndId,
                    originalUetr, debtorAgent, status, instructingAgent, instructedAgent);
        }
    }

    private static final class StatusRequestInputs {
        MsgId msgId;
        Instant createdAt;
        MsgId originalMsgId;
        TxId originalTxId;
        EndToEndId originalEndToEndId;
        Uetr originalUetr;
        Bic debtorAgent;
        Bic instructingAgent;
        Bic instructedAgent;

        StatusRequest build() {
            return new StatusRequest(msgId, createdAt, originalMsgId, originalTxId, originalEndToEndId, originalUetr,
                    debtorAgent, instructingAgent, instructedAgent);
        }
    }
}
