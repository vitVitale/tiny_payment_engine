package com.tpe.paymentorchestrator.api.cases;

import com.tpe.contracts.payment.PaymentView;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentorchestrator.api.FieldError;
import com.tpe.paymentorchestrator.api.PaymentViews;
import com.tpe.paymentorchestrator.api.ValidationFailedException;
import com.tpe.paymentorchestrator.application.cases.CaseNotFoundException;
import com.tpe.paymentorchestrator.application.cases.CaseResolutions;
import com.tpe.paymentorchestrator.application.cases.ExceptionCases;
import com.tpe.paymentorchestrator.application.payments.PaymentReader;
import com.tpe.paymentorchestrator.domain.payment.CaseResolution;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCase;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exception case API, role {@code BACKOFFICE_OPERATOR} (specs/11-payment-orchestrator.md §3.2): list and inspect
 * cases, and resolve one, recorded with the operator's {@code sub}. Errors: {@code 404 CASE_NOT_FOUND},
 * {@code 409 CASE_ALREADY_RESOLVED} / {@code CASE_OUTDATED}, {@code 422 RESOLUTION_NOT_ALLOWED},
 * {@code 400 VALIDATION_FAILED}.
 */
@RestController
@RequestMapping(ExceptionCaseController.CASES)
@PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
@SecurityRequirement(name = "staffJwt")
class ExceptionCaseController {

    static final String CASES = "/v1/exception-cases";

    /** The page size and the largest {@code limit}. */
    static final int MAX_PAGE = 100;

    /** At most this long, as {@code exception_cases.comment}. */
    static final int MAX_COMMENT = 1000;

    /** A case as listed and as resolved. */
    record CaseView(UUID caseId, UUID paymentId, String type, String status, Map<String, Object> details,
            Instant openedAt, String resolution, String resolvedBy, Instant resolvedAt, String comment) {

        static CaseView of(ExceptionCase c) {
            return new CaseView(c.caseId(), c.paymentId() == null ? null : c.paymentId().value(), c.type().name(),
                    c.status().name(), c.details(), c.openedAt(),
                    c.resolution() == null ? null : c.resolution().name(), c.resolvedBy(), c.resolvedAt(),
                    c.comment());
        }
    }

    /** @param nextCursor {@code null} on the last page */
    record CasePage(List<CaseView> cases, String nextCursor) {}

    /**
     * A case with its payment, the payment's transitions and the CSM events received for it; the last three are
     * absent for a case without a payment.
     */
    record CaseDetail(UUID caseId, UUID paymentId, String type, String status, Map<String, Object> details,
            Instant openedAt, String resolution, String resolvedBy, Instant resolvedAt, String comment,
            PaymentView payment, List<ExceptionCases.Transition> transitions,
            List<ExceptionCases.CsmEvent> csmEvents) {}

    /**
     * @param resolution one the case's type allows (§3.2)
     * @param reasonCode an ISO 20022 status reason, required for {@code CONFIRMED_REJECTED}
     * @param comment optional, at most 1000 characters
     */
    record ResolveRequest(String resolution, String reasonCode, String comment) {}

    private final ExceptionCases cases;
    private final CaseResolutions resolutions;
    private final PaymentReader payments;

    ExceptionCaseController(ExceptionCases cases, CaseResolutions resolutions, PaymentReader payments) {
        this.cases = cases;
        this.resolutions = resolutions;
        this.payments = payments;
    }

    /** Oldest first; {@code nextCursor} as given leads to the next page. */
    @GetMapping
    CasePage listCases(@RequestParam(required = false) String status, @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        List<FieldError> errors = new ArrayList<>();
        ExceptionCase.Status byStatus = parse(ExceptionCase.Status.class, "status", status, errors);
        ExceptionCaseType byType = parse(ExceptionCaseType.class, "type", type, errors);
        UUID after = CaseCursor.decode(cursor, errors);
        if (limit != null && (limit < 1 || limit > MAX_PAGE)) {
            errors.add(new FieldError("limit", "OUT_OF_RANGE"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        ExceptionCases.Page page = cases.list(byStatus, byType, after, limit == null ? MAX_PAGE : limit);
        List<CaseView> views = page.cases().stream().map(CaseView::of).toList();
        return new CasePage(views, page.more() ? CaseCursor.encode(views.getLast().caseId()) : null);
    }

    @GetMapping("/{caseId}")
    CaseDetail getCase(@PathVariable UUID caseId) {
        ExceptionCase c = cases.find(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
        CaseView view = CaseView.of(c);
        PaymentView payment = null;
        List<ExceptionCases.Transition> transitions = null;
        List<ExceptionCases.CsmEvent> csmEvents = null;
        if (c.paymentId() != null) {
            Payment found = payments.get(c.paymentId());
            payment = PaymentViews.of(found);
            transitions = cases.transitions(c.paymentId());
            csmEvents = cases.csmEvents(found.debtor().agentBic(), found.txId());
        }
        return new CaseDetail(view.caseId(), view.paymentId(), view.type(), view.status(), view.details(),
                view.openedAt(), view.resolution(), view.resolvedBy(), view.resolvedAt(), view.comment(), payment,
                transitions, csmEvents);
    }

    @PostMapping("/{caseId}/resolve")
    CaseView resolveCase(@PathVariable UUID caseId, @RequestBody ResolveRequest request,
            @AuthenticationPrincipal Jwt operator) {
        List<FieldError> errors = new ArrayList<>();
        CaseResolution resolution = request.resolution() == null ? null
                : parse(CaseResolution.class, "resolution", request.resolution(), errors);
        if (request.resolution() == null) {
            errors.add(new FieldError("resolution", "REQUIRED"));
        }
        StatusReason reason = null;
        if (resolution != null && resolution.needsReasonCode()) {
            reason = reasonCode(request.reasonCode(), errors);
        }
        if (request.comment() != null && request.comment().length() > MAX_COMMENT) {
            errors.add(new FieldError("comment", "TOO_LONG"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return CaseView.of(resolutions.resolve(caseId, resolution, reason, request.comment(), operator.getSubject()));
    }

    /** A known ISO 20022 reason code; never kept as an unknown one. */
    private static StatusReason reasonCode(String code, List<FieldError> errors) {
        if (code == null || code.isBlank()) {
            errors.add(new FieldError("reasonCode", "REQUIRED"));
            return null;
        }
        if (!(ReasonCode.parse(code) instanceof ReasonCode known)) {
            errors.add(new FieldError("reasonCode", "INVALID_REASON_CODE"));
            return null;
        }
        return known;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String field, String value, List<FieldError> errors) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(field, "INVALID_VALUE"));
            return null;
        }
    }
}
