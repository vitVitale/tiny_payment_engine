package com.tpe.vopservice.api.verify;

import com.tpe.contracts.vop.ReferenceCheckRequest;
import com.tpe.contracts.vop.ReferenceCheckResponse;
import com.tpe.contracts.vop.VerificationRequest;
import com.tpe.contracts.vop.VerificationResponse;
import com.tpe.contracts.vop.VopReferenceProblem;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.api.FieldError;
import com.tpe.vopservice.api.InternalApi;
import com.tpe.vopservice.api.ValidationFailedException;
import com.tpe.vopservice.application.references.ReferenceChecking;
import com.tpe.vopservice.application.requesting.PayeeVerification;
import com.tpe.vopservice.application.requesting.RequestingService;
import com.tpe.vopservice.domain.references.IssuedReference;
import com.tpe.vopservice.domain.references.ReferenceCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal payee verification API, called by payment-api (specs/13-vop-service.md §3.1, UC-02, UC-01 4). Request
 * and response records are in {@code contracts}. Unauthenticated until M5 (ADR-012).
 */
@InternalApi
@RestController
class VerificationController {

    /** {@code vop_results.payee_name}. */
    static final int MAX_PAYEE_NAME = 70;
    /** An ISO identifier. */
    static final int MAX_ORGANISATION_ID = 35;

    private final RequestingService requesting;
    private final ReferenceChecking checking;

    VerificationController(RequestingService requesting, ReferenceChecking checking) {
        this.requesting = requesting;
        this.checking = checking;
    }

    /** Always {@code 201} once the request is valid; {@code 422 PAYEE_BANK_UNKNOWN} for a bank not in the directory. */
    @PostMapping("/v1/verifications")
    @ResponseStatus(HttpStatus.CREATED)
    VerificationResponse verify(@RequestBody VerificationRequest request) {
        List<FieldError> errors = paymentFields(request.debtorIban(), request.payeeIban(), request.payeeName());
        String organisationId = request.payeeOrganisationId() == null || request.payeeOrganisationId().isBlank()
                ? null
                : request.payeeOrganisationId().strip();
        if (organisationId != null && organisationId.length() > MAX_ORGANISATION_ID) {
            errors.add(new FieldError("payeeOrganisationId", "TOO_LONG"));
        }
        failOn(errors);
        IssuedReference reference = requesting.verify(new PayeeVerification(request.debtorIban(),
                request.payeeIban(), request.payeeName(), organisationId));
        return new VerificationResponse(reference.vopReference(), VopResult.valueOf(reference.result().name()),
                reference.actualName(), reference.expiresAt(), reference.respondedBy());
    }

    /** {@code 200} with {@code valid}, or with the problem; an unknown reference is {@code UNKNOWN}, not 404. */
    @PostMapping("/v1/verifications/{vopReference}/check")
    ReferenceCheckResponse check(@PathVariable String vopReference, @RequestBody ReferenceCheckRequest request) {
        failOn(paymentFields(request.debtorIban(), request.payeeIban(), request.payeeName()));
        ReferenceCheck check = checking.check(vopReference, request.debtorIban(), request.payeeIban(),
                request.payeeName());
        return check.valid()
                ? ReferenceCheckResponse.valid(VopResult.valueOf(check.result().name()))
                : ReferenceCheckResponse.invalid(VopReferenceProblem.valueOf(check.problem().name()));
    }

    /** The fields both requests share: the payment a reference is issued for and checked against. */
    private static List<FieldError> paymentFields(Iban debtorIban, Iban payeeIban, String payeeName) {
        List<FieldError> errors = new ArrayList<>();
        if (debtorIban == null) {
            errors.add(new FieldError("debtorIban", "REQUIRED"));
        }
        if (payeeIban == null) {
            errors.add(new FieldError("payeeIban", "REQUIRED"));
        }
        if (payeeName == null || payeeName.isBlank()) {
            errors.add(new FieldError("payeeName", "REQUIRED"));
        } else if (payeeName.length() > MAX_PAYEE_NAME) {
            errors.add(new FieldError("payeeName", "TOO_LONG"));
        }
        return errors;
    }

    private static void failOn(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
    }
}
