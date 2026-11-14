package com.tpe.paymentapi.application.verification;

import com.tpe.contracts.vop.VerificationRequest;
import com.tpe.contracts.vop.VerificationResponse;
import com.tpe.paymentapi.application.submission.VopUnavailableException;
import com.tpe.paymentapi.application.validation.FieldError;
import com.tpe.paymentapi.application.validation.ValidationFailedException;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.ValidationCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Payee verification for the payer (specs/10-payment-api.md §3.4, UC-02): the request is checked here, so vop-service
 * sees no request it would refuse (UC-02 1a: an IBAN failing its checksum is a field error without any call), then
 * forwarded. vop-service's answer is returned unchanged. The fields are vop-service's (13 §3.1): IBANs required and
 * valid, the payee name required and at most 70 characters, the organisation id at most 35, blank being none.
 */
public class PayeeVerification {

    static final int MAX_NAME = 70;
    static final int MAX_ORGANISATION_ID = 35;
    private static final String TOO_LONG = ValidationCode.TOO_LONG.name();

    private static final Logger LOG = LoggerFactory.getLogger(PayeeVerification.class);

    private final PayeeVerifications vop;

    public PayeeVerification(PayeeVerifications vop) {
        this.vop = vop;
    }

    /** {@code POST /v1/payee-verifications} as the channel sent it. */
    public record Request(String debtorIban, String payeeIban, String payeeName, String payeeOrganisationId) {

        /** IBANs and names are personal data (specs/26-observability.md §4.4). */
        @Override
        public String toString() {
            return "PayeeVerification.Request[…]";
        }
    }

    /**
     * @throws ValidationFailedException a field is missing or invalid
     * @throws PayeeBankUnknownException vop-service has no bank for the payee IBAN
     * @throws VopUnavailableException vop-service did not answer
     */
    public VerificationResponse verify(Request request) {
        List<FieldError> errors = new ArrayList<>();
        Iban debtor = iban("debtorIban", request.debtorIban(), errors);
        Iban payee = iban("payeeIban", request.payeeIban(), errors);
        String name = blankToNull(request.payeeName());
        if (name == null) {
            errors.add(new FieldError("payeeName", FieldError.REQUIRED));
        } else if (name.length() > MAX_NAME) {
            errors.add(new FieldError("payeeName", TOO_LONG));
        }
        String organisationId = blankToNull(request.payeeOrganisationId());
        if (organisationId != null && organisationId.length() > MAX_ORGANISATION_ID) {
            errors.add(new FieldError("payeeOrganisationId", TOO_LONG));
        }
        if (!errors.isEmpty()) {
            errors.sort(Comparator.comparing(FieldError::field).thenComparing(FieldError::code));
            throw new ValidationFailedException(errors);
        }
        return switch (vop.verify(new VerificationRequest(debtor, payee, name, organisationId))) {
            case PayeeVerifications.Verified verified -> verified.response();
            case PayeeVerifications.PayeeBankUnknown unknown -> throw new PayeeBankUnknownException();
            case PayeeVerifications.Unavailable unavailable -> {
                LOG.warn("vop-service unavailable for a verification: {} {}", unavailable.failure(),
                        unavailable.why());
                throw new VopUnavailableException(unavailable.why());
            }
        };
    }

    private static Iban iban(String field, String value, List<FieldError> errors) {
        if (blankToNull(value) == null) {
            errors.add(new FieldError(field, FieldError.REQUIRED));
            return null;
        }
        try {
            return new Iban(value);
        } catch (DomainValidationException e) {
            errors.add(new FieldError(field, e.code().name()));
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
