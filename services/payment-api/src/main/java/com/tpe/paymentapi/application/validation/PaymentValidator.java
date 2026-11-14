package com.tpe.paymentapi.application.validation;

import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.RemittanceInformation;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The payload validator (specs/10-payment-api.md §5.2, UC-01 3): presence by Bean Validation, then each present value
 * through its {@code payment-domain} value object, the creditor BIC against the directory and debtor ≠ creditor. All
 * field errors are reported at once ({@code 400}), ordered by field; only a payment without them is checked for the creditor bank's
 * reachability ({@code 422}).
 */
public class PaymentValidator {

    /** 999 999 999.99 EUR (§3.5 {@code INVALID_AMOUNT}). */
    static final long MAX_AMOUNT_MINOR = 99_999_999_999L;

    private static final String EUR = "EUR";

    private final Validator beanValidation;
    private final BankDirectory directory;

    public PaymentValidator(Validator beanValidation, BankDirectory directory) {
        this.beanValidation = beanValidation;
        this.directory = directory;
    }

    /**
     * @throws ValidationFailedException with every field error
     * @throws CreditorAgentNotReachableException for a creditor bank not in the directory or not SCT Inst reachable
     */
    public ValidatedPayment validate(PaymentInstruction instruction) {
        List<FieldError> errors = new ArrayList<>(beanValidation.validate(instruction).stream()
                .map(PaymentValidator::fieldError)
                .toList());
        EndToEndId endToEndId = parse("endToEndId", instruction.endToEndId(), EndToEndId::new, errors);
        PartyName debtorName = null;
        Iban debtorIban = null;
        if (instruction.debtor() != null) {
            debtorName = parse("debtor.name", instruction.debtor().name(), PartyName::new, errors);
            debtorIban = parse("debtor.iban", instruction.debtor().iban(), Iban::new, errors);
        }
        PartyName creditorName = null;
        Iban creditorIban = null;
        Bic creditorBic = null;
        if (instruction.creditor() != null) {
            creditorName = parse("creditor.name", instruction.creditor().name(), PartyName::new, errors);
            creditorIban = parse("creditor.iban", instruction.creditor().iban(), Iban::new, errors);
            creditorBic = parse("creditor.bic", instruction.creditor().bic(), Bic::new, errors);
        }
        Money amount = instruction.amount() == null ? null : amount(instruction.amount(), errors);
        RemittanceInformation remittance = parse("remittanceInformation", instruction.remittanceInformation(),
                RemittanceInformation::new, errors);
        Optional<BankEntry> creditorBank = creditorIban == null ? Optional.empty() : directory.lookup(creditorIban);
        if (creditorBic != null && creditorBank.isPresent()
                && !creditorBank.get().bic().bic8().equals(creditorBic.bic8())) {
            errors.add(new FieldError("creditor.bic", FieldError.INVALID_BIC));
        }
        if (debtorIban != null && debtorIban.equals(creditorIban)) {
            errors.add(new FieldError("creditor.iban", FieldError.SAME_DEBTOR_AND_CREDITOR));
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(FieldError::field).thenComparing(FieldError::code));
            throw new ValidationFailedException(errors);
        }
        if (creditorBank.isEmpty() || !creditorBank.get().sctInstReachable()) {
            throw new CreditorAgentNotReachableException(creditorBank.isEmpty()
                    ? "creditor bank " + creditorIban.bankCode() + " is not in the directory"
                    : "creditor bank " + creditorIban.bankCode() + " is not reachable for SCT Inst");
        }
        return new ValidatedPayment(endToEndId, debtorName, debtorIban, creditorName, creditorIban, creditorBic,
                amount, remittance, blankToNull(instruction.vopReference()));
    }

    /** {@code value} and {@code currency} separately: {@link Money#parse} knows EUR only. */
    private static Money amount(PaymentInstruction.Amount amount, List<FieldError> errors) {
        if (amount.currency() != null && !amount.currency().isBlank() && !EUR.equals(amount.currency())) {
            errors.add(new FieldError("amount.currency", FieldError.UNSUPPORTED_CURRENCY));
        }
        Money money = parse("amount.value", amount.value(), Money::parse, errors);
        if (money != null && money.minor() > MAX_AMOUNT_MINOR) {
            errors.add(new FieldError("amount.value", FieldError.INVALID_AMOUNT));
            return null;
        }
        return money;
    }

    /** A blank value is absent: Bean Validation has reported it if it is required. */
    private static <T> T parse(String field, String value, Function<String, T> valueObject, List<FieldError> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueObject.apply(value);
        } catch (DomainValidationException e) {
            errors.add(new FieldError(field, e.code().name()));
            return null;
        }
    }

    private static FieldError fieldError(ConstraintViolation<PaymentInstruction> violation) {
        return new FieldError(violation.getPropertyPath().toString(), violation.getMessage());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
