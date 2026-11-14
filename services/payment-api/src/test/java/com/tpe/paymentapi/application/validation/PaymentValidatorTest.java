package com.tpe.paymentapi.application.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.paymentapi.application.validation.PaymentInstruction.Amount;
import com.tpe.paymentapi.application.validation.PaymentInstruction.Creditor;
import com.tpe.paymentapi.application.validation.PaymentInstruction.Debtor;
import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.InMemoryBankDirectory;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.RemittanceInformation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The field rules of specs/10-payment-api.md §5.2, one failing example each with its §3.5 field code. */
class PaymentValidatorTest {

    private static final String DEBTOR = "DE36100100100000000001";
    private static final String PEER = "DE63200200200000000001";
    /** A bank in the directory that is not reachable for SCT Inst. */
    private static final String UNREACHABLE = "DE90300300300000000001";
    /** A bank code not in the directory. */
    private static final String UNKNOWN_BANK = "DE89370400440532013000";

    private static final ValidatorFactory BEAN_VALIDATION = Validation.buildDefaultValidatorFactory();

    private final PaymentValidator validator = new PaymentValidator(BEAN_VALIDATION.getValidator(),
            new InMemoryBankDirectory("10010010", new Bic("TPEBDEFFXXX"), List.of(
                    new BankEntry("10010010", new Bic("TPEBDEFFXXX"), true, Optional.empty()),
                    new BankEntry("20020020", new Bic("PEERDEFFXXX"), true, Optional.empty()),
                    new BankEntry("30030030", new Bic("SLOWDEFFXXX"), false, Optional.empty()))));

    @AfterAll
    static void close() {
        BEAN_VALIDATION.close();
    }

    static PaymentInstruction valid() {
        return new PaymentInstruction("INV-2026-0042", new Debtor("Anna Schmidt", DEBTOR),
                new Creditor("Max Mustermann", PEER, "PEERDEFFXXX"), new Amount("123.45", "EUR"), "Invoice 42",
                "VOP-01JABCDEFGHJKMNPQRSTVWXYZ0");
    }

    @Test
    void aValidInstructionBecomesTheDomainValues() {
        ValidatedPayment payment = validator.validate(valid());

        assertThat(payment).isEqualTo(new ValidatedPayment(new EndToEndId("INV-2026-0042"),
                new PartyName("Anna Schmidt"), new Iban(DEBTOR), new PartyName("Max Mustermann"), new Iban(PEER),
                new Bic("PEERDEFFXXX"), new Money(12_345, Currency.getInstance("EUR")),
                new RemittanceInformation("Invoice 42"), "VOP-01JABCDEFGHJKMNPQRSTVWXYZ0"));
    }

    @Test
    void theOptionalFieldsMayBeAbsentOrBlank() {
        PaymentInstruction instruction = with(valid(), i -> new PaymentInstruction(i.endToEndId(), i.debtor(),
                new Creditor("Max Mustermann", PEER, null), i.amount(), " ", i.vopReference()));

        ValidatedPayment payment = validator.validate(instruction);

        assertThat(payment.creditorBic()).isNull();
        assertThat(payment.remittanceInformation()).isNull();
    }

    @Test
    void aBicGivenAsBic8OfTheDirectoryBicIsAccepted() {
        assertThat(validator.validate(creditor("Max Mustermann", PEER, "PEERDEFF")).creditorBic())
                .isEqualTo(new Bic("PEERDEFF"));
    }

    static Stream<Arguments> fieldRules() {
        return Stream.of(
                rule("no endToEndId", i -> e2e(i, null), "endToEndId", "REQUIRED"),
                rule("endToEndId over 35", i -> e2e(i, "E".repeat(36)), "endToEndId", "TOO_LONG"),
                rule("endToEndId outside the SEPA set", i -> e2e(i, "INV_42"), "endToEndId", "INVALID_CHARACTERS"),
                rule("no debtor", i -> new PaymentInstruction(i.endToEndId(), null, i.creditor(), i.amount(),
                        i.remittanceInformation(), i.vopReference()), "debtor", "REQUIRED"),
                rule("no debtor name", i -> debtor(i, null, DEBTOR), "debtor.name", "REQUIRED"),
                rule("debtor name over 70", i -> debtor(i, "A".repeat(71), DEBTOR), "debtor.name", "TOO_LONG"),
                rule("debtor name outside the SEPA set", i -> debtor(i, "Anna Schmidt & Co", DEBTOR), "debtor.name",
                        "INVALID_CHARACTERS"),
                rule("no debtor IBAN", i -> debtor(i, "Anna Schmidt", " "), "debtor.iban", "REQUIRED"),
                rule("debtor IBAN checksum", i -> debtor(i, "Anna Schmidt", "DE36100100100000000002"), "debtor.iban",
                        "INVALID_IBAN"),
                rule("no creditor", i -> new PaymentInstruction(i.endToEndId(), i.debtor(), null, i.amount(),
                        i.remittanceInformation(), i.vopReference()), "creditor", "REQUIRED"),
                rule("no creditor name", i -> creditor(null, PEER, null), "creditor.name", "REQUIRED"),
                rule("creditor name over 70", i -> creditor("M".repeat(71), PEER, null), "creditor.name",
                        "TOO_LONG"),
                rule("creditor name outside the SEPA set", i -> creditor("Max Müller", PEER, null), "creditor.name",
                        "INVALID_CHARACTERS"),
                rule("no creditor IBAN", i -> creditor("Max Mustermann", null, null), "creditor.iban", "REQUIRED"),
                rule("creditor IBAN outside SEPA", i -> creditor("Max Mustermann", "US64SVBKUS6S3300958879", null),
                        "creditor.iban", "INVALID_IBAN"),
                rule("creditor BIC format", i -> creditor("Max Mustermann", PEER, "PEER-DEFF"), "creditor.bic",
                        "INVALID_BIC"),
                rule("creditor BIC not the directory's", i -> creditor("Max Mustermann", PEER, "TPEBDEFFXXX"),
                        "creditor.bic", "INVALID_BIC"),
                rule("same debtor and creditor", i -> creditor("Anna Schmidt", DEBTOR, null), "creditor.iban",
                        "SAME_DEBTOR_AND_CREDITOR"),
                rule("no amount", i -> new PaymentInstruction(i.endToEndId(), i.debtor(), i.creditor(), null,
                        i.remittanceInformation(), i.vopReference()), "amount", "REQUIRED"),
                rule("no amount value", i -> amount(i, null, "EUR"), "amount.value", "REQUIRED"),
                rule("zero amount", i -> amount(i, "0.00", "EUR"), "amount.value", "INVALID_AMOUNT"),
                rule("negative amount", i -> amount(i, "-1.00", "EUR"), "amount.value", "INVALID_AMOUNT"),
                rule("three decimals", i -> amount(i, "1.005", "EUR"), "amount.value", "INVALID_AMOUNT"),
                rule("over 999 999 999.99", i -> amount(i, "1000000000.00", "EUR"), "amount.value",
                        "INVALID_AMOUNT"),
                rule("no currency", i -> amount(i, "1.00", null), "amount.currency", "REQUIRED"),
                rule("not EUR", i -> amount(i, "1.00", "USD"), "amount.currency", "UNSUPPORTED_CURRENCY"),
                rule("remittance over 140", i -> remittance(i, "R".repeat(141)), "remittanceInformation", "TOO_LONG"),
                rule("remittance outside the SEPA set", i -> remittance(i, "Rechnung Nr. 42 – März"),
                        "remittanceInformation", "INVALID_CHARACTERS"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldRules")
    void eachFieldRuleFailsWithItsCode(String rule, UnaryOperator<PaymentInstruction> change, String field,
            String code) {
        assertThatThrownBy(() -> validator.validate(change.apply(valid())))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.errors()).containsExactly(new FieldError(field, code)));
    }

    @Test
    void everyFieldErrorIsReportedAtOnce() {
        PaymentInstruction instruction = new PaymentInstruction(null, new Debtor(null, "DE00"),
                new Creditor("Max Mustermann", PEER, null), new Amount("1.005", "USD"), null, null);

        assertThatThrownBy(() -> validator.validate(instruction))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.errors())
                        .containsExactlyInAnyOrder(new FieldError("endToEndId", "REQUIRED"),
                                new FieldError("debtor.name", "REQUIRED"), new FieldError("debtor.iban", "INVALID_IBAN"),
                                new FieldError("amount.value", "INVALID_AMOUNT"),
                                new FieldError("amount.currency", "UNSUPPORTED_CURRENCY")));
    }

    @Test
    void aCreditorBankNotInTheDirectoryIsNotReachable() {
        assertThatThrownBy(() -> validator.validate(creditor("Max Mustermann", UNKNOWN_BANK, null)))
                .isInstanceOf(CreditorAgentNotReachableException.class);
    }

    @Test
    void aCreditorBankNotReachableForSctInstIsNotReachable() {
        assertThatThrownBy(() -> validator.validate(creditor("Max Mustermann", UNREACHABLE, null)))
                .isInstanceOf(CreditorAgentNotReachableException.class);
    }

    @Test
    void fieldErrorsComeBeforeReachability() {
        assertThatThrownBy(() -> validator.validate(amount(creditor("Max Mustermann", UNKNOWN_BANK, null), "0",
                "EUR")))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void aMissingVopReferenceIsLeftToTheReferenceCheck() {
        assertThat(validator.validate(with(valid(), i -> new PaymentInstruction(i.endToEndId(), i.debtor(),
                i.creditor(), i.amount(), i.remittanceInformation(), null))).vopReference()).isNull();
    }

    private static Arguments rule(String name, UnaryOperator<PaymentInstruction> change, String field, String code) {
        return Arguments.of(name, change, field, code);
    }

    private static PaymentInstruction with(PaymentInstruction instruction, UnaryOperator<PaymentInstruction> change) {
        return change.apply(instruction);
    }

    private static PaymentInstruction e2e(PaymentInstruction i, String endToEndId) {
        return new PaymentInstruction(endToEndId, i.debtor(), i.creditor(), i.amount(), i.remittanceInformation(),
                i.vopReference());
    }

    private static PaymentInstruction debtor(PaymentInstruction i, String name, String iban) {
        return new PaymentInstruction(i.endToEndId(), new Debtor(name, iban), i.creditor(), i.amount(),
                i.remittanceInformation(), i.vopReference());
    }

    private static PaymentInstruction creditor(String name, String iban, String bic) {
        PaymentInstruction i = valid();
        return new PaymentInstruction(i.endToEndId(), i.debtor(), new Creditor(name, iban, bic), i.amount(),
                i.remittanceInformation(), i.vopReference());
    }

    private static PaymentInstruction amount(PaymentInstruction i, String value, String currency) {
        return new PaymentInstruction(i.endToEndId(), i.debtor(), i.creditor(), new Amount(value, currency),
                i.remittanceInformation(), i.vopReference());
    }

    private static PaymentInstruction remittance(PaymentInstruction i, String text) {
        return new PaymentInstruction(i.endToEndId(), i.debtor(), i.creditor(), i.amount(), text,
                i.vopReference());
    }
}
