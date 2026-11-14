package com.tpe.paymentapi.application.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.tpe.iso20022model.IsoCodec;
import com.tpe.iso20022model.XsdValidator;
import com.tpe.paymentapi.Pain001Sample;
import com.tpe.paymentapi.application.validation.PaymentInstruction;
import com.tpe.paymentdomain.MutableClock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Pain001ReaderTest {

    private static final String REFERENCE = "VOP-01JABCDEFGHJKMNPQRSTVW0001";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
    private final Pain001Reader reader = new Pain001Reader(new XsdValidator(), new IsoCodec(), clock);

    /** API-AC-02: the values of the JSON body, from their pain.001 elements. */
    @Test
    void aValidDocumentIsTheInstructionTheEquivalentJsonIs() {
        Pain001Reader.Pain001 read = reader.read(Pain001Sample.valid().xml(), REFERENCE);

        assertThat(read.messageId()).isEqualTo(Pain001Sample.MESSAGE_ID);
        assertThat(read.instruction()).isEqualTo(new PaymentInstruction("INV-2026-0042",
                new PaymentInstruction.Debtor("Anna Schmidt", "DE36100100100000000001"),
                new PaymentInstruction.Creditor("Max Mustermann", "DE63200200200000000001", "PEERDEFFXXX"),
                new PaymentInstruction.Amount("123.45", "EUR"), "Invoice 42", REFERENCE));
    }

    @Test
    void absentOptionalElementsAreAbsentValues() {
        String xml = Pain001Sample.valid().without("CdtrAgt").without("RmtInf").xml();

        PaymentInstruction instruction = reader.read(xml, null).instruction();

        assertThat(instruction.creditor().bic()).isNull();
        assertThat(instruction.remittanceInformation()).isNull();
        assertThat(instruction.vopReference()).isNull();
    }

    /** Left to the validator, which reports them as field errors of the JSON paths. */
    @Test
    void absentPartiesAndAccountsAreMissingValues() {
        String xml = Pain001Sample.valid().without("Cdtr").without("CdtrAcct").xml();

        PaymentInstruction instruction = reader.read(xml, REFERENCE).instruction();

        assertThat(instruction.creditor()).isEqualTo(new PaymentInstruction.Creditor(null, null, "PEERDEFFXXX"));
    }

    @Test
    void anAmountOfMoreDecimalsThanCentsIsReadAsWritten() {
        String xml = Pain001Sample.valid().with(">123.45<", ">123.456<").xml();

        assertThat(reader.read(xml, REFERENCE).instruction().amount().value()).isEqualTo("123.456");
    }

    @Test
    void theEndToEndIdIsNotProvidedWhenBlank() {
        String xml = Pain001Sample.valid().withValue("EndToEndId", "INV-2026-0042", " ").xml();

        assertThat(reader.read(xml, REFERENCE).instruction().endToEndId()).isEqualTo("NOTPROVIDED");
    }

    @Test
    void theLocalInstrumentOfTheTransactionOverridesThePaymentInformationOne() {
        Pain001Sample instructionSdcl = Pain001Sample.valid().withValue("Cd", "INST", "SDCL");
        String transactionInst = instructionSdcl
                .with("</PmtId>", "</PmtId><PmtTpInf><LclInstrm><Cd>INST</Cd></LclInstrm></PmtTpInf>").xml();
        String transactionSdcl = Pain001Sample.valid()
                .with("</PmtId>", "</PmtId><PmtTpInf><LclInstrm><Cd>SDCL</Cd></LclInstrm></PmtTpInf>").xml();

        assertThat(reader.read(transactionInst, null).instruction()).isNotNull();
        assertThatExceptionOfType(Pain001Reader.UnsupportedVariantException.class)
                .isThrownBy(() -> reader.read(transactionSdcl, null));
    }

    @Test
    void aTransactionPaymentTypeWithoutALocalInstrumentKeepsThePaymentInformationOne() {
        String xml = Pain001Sample.valid()
                .with("</PmtId>", "</PmtId><PmtTpInf><InstrPrty>HIGH</InstrPrty></PmtTpInf>").xml();

        assertThat(reader.read(xml, null).instruction()).isNotNull();
    }

    /** Today in UTC, as the interbank settlement date is (Q-12), whatever the offset the client wrote. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-11-14T08:00:00Z", "2026-11-15T00:30:00+02:00", "2026-11-14T08:00:00"})
    void aRequestedExecutionDateTimeOfTodayIsAccepted(String dateTime) {
        String xml = Pain001Sample.valid().with("<Dt>2026-11-14</Dt>", "<DtTm>" + dateTime + "</DtTm>").xml();

        reader.requireToday(reader.read(xml, null));
    }

    @Test
    void aRequestedExecutionDateTimeOfTomorrowInUtcIsAnUnsupportedVariant() {
        String xml = Pain001Sample.valid()
                .with("<Dt>2026-11-14</Dt>", "<DtTm>2026-11-14T23:30:00-02:00</DtTm>").xml();
        Pain001Reader.Pain001 read = reader.read(xml, null);

        assertThatExceptionOfType(Pain001Reader.UnsupportedVariantException.class)
                .isThrownBy(() -> reader.requireToday(read));
    }

    @Test
    void theRequestedExecutionDateIsReadAndTodayIsAccepted() {
        Pain001Reader.Pain001 read = reader.read(Pain001Sample.valid().xml(), null);

        assertThat(read.requestedExecutionDate()).isEqualTo(LocalDate.of(2026, 11, 14));
        reader.requireToday(read);
    }

    @Test
    void twoTransactionsAreAnUnsupportedVariant() {
        assertUnsupported(Pain001Sample.valid().withTwoTransactions());
    }

    @Test
    void twoPaymentInformationsAreAnUnsupportedVariant() {
        assertUnsupported(Pain001Sample.valid().withTwoPaymentInformations());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pain.001.001.03", "pain.001.001.11", "pain.002.001.10"})
    void aDocumentOfAnotherVersionIsAnUnsupportedVariant(String version) {
        assertUnsupported(Pain001Sample.valid().with("pain.001.001.09", version));
    }

    @Test
    void aLocalInstrumentOtherThanInstIsAnUnsupportedVariant() {
        assertUnsupported(Pain001Sample.valid().withValue("Cd", "INST", "SDCL"));
    }

    @Test
    void noLocalInstrumentIsAnUnsupportedVariant() {
        assertUnsupported(Pain001Sample.valid().without("PmtTpInf"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-13", "2026-11-15"})
    void aRequestedExecutionDateOtherThanTodayIsAnUnsupportedVariant(String date) {
        Pain001Reader.Pain001 read = reader.read(Pain001Sample.valid().withValue("Dt", "2026-11-14", date).xml(),
                REFERENCE);

        assertThatExceptionOfType(Pain001Reader.UnsupportedVariantException.class)
                .isThrownBy(() -> reader.requireToday(read));
    }

    /** A schema-invalid MsgId would make the FF01 pain.002 schema-invalid too. */
    @ParameterizedTest
    @ValueSource(strings = {"M-123456789012345678901234567890-TOO-LONG", ""})
    void aSchemaInvalidMessageIdIsNotProvided(String messageId) {
        String xml = Pain001Sample.valid().withValue("MsgId", Pain001Sample.MESSAGE_ID, messageId).xml();

        assertThatExceptionOfType(Pain001Reader.SchemaInvalidException.class)
                .isThrownBy(() -> reader.read(xml, REFERENCE))
                .satisfies(e -> assertThat(e.messageId()).isEqualTo(Pain001Reader.NOT_PROVIDED));
    }

    @Test
    void aSchemaInvalidDocumentIsRejectedWithItsMessageIdAndViolations() {
        String xml = Pain001Sample.valid().withValue("IBAN", "DE36100100100000000001", "not an IBAN").xml();

        assertThatExceptionOfType(Pain001Reader.SchemaInvalidException.class)
                .isThrownBy(() -> reader.read(xml, REFERENCE))
                .satisfies(e -> {
                    assertThat(e.messageId()).isEqualTo(Pain001Sample.MESSAGE_ID);
                    assertThat(e.violations()).extracting(XsdValidator.Violation::code)
                            .contains("cvc-pattern-valid");
                });
    }

    /** API-AC-11. */
    @Test
    void aDocumentWithADoctypeIsRejectedWithoutReadingIt() {
        String xml = Pain001Sample.valid()
                .with("<Document ", "<!DOCTYPE Document [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>\n<Document ")
                .with("<Nm>Anna Schmidt</Nm>", "<Nm>&e;</Nm>")
                .xml();

        assertThatExceptionOfType(Pain001Reader.SchemaInvalidException.class)
                .isThrownBy(() -> reader.read(xml, REFERENCE))
                .satisfies(e -> {
                    assertThat(e.messageId()).isEqualTo(Pain001Reader.NOT_PROVIDED);
                    assertThat(e.violations()).extracting(XsdValidator.Violation::code)
                            .containsExactly(XsdValidator.DOCTYPE_DISALLOWED);
                });
    }

    @Test
    void inputThatIsNotXmlIsRejectedWithoutAMessageId() {
        assertThatExceptionOfType(Pain001Reader.SchemaInvalidException.class)
                .isThrownBy(() -> reader.read("{\"endToEndId\":\"x\"}", REFERENCE))
                .satisfies(e -> assertThat(e.messageId()).isEqualTo(Pain001Reader.NOT_PROVIDED));
    }

    private void assertUnsupported(Pain001Sample sample) {
        assertThatExceptionOfType(Pain001Reader.UnsupportedVariantException.class)
                .isThrownBy(() -> reader.read(sample.xml(), REFERENCE));
    }
}
