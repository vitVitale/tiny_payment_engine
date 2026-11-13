package com.tpe.riskservice.api.assessment;

import com.tpe.contracts.Direction;
import com.tpe.contracts.Party;
import com.tpe.contracts.risk.AssessmentRequest;
import com.tpe.contracts.risk.AssessmentResponse;
import com.tpe.contracts.risk.RiskDecision;
import com.tpe.contracts.risk.RuleHit;
import com.tpe.riskservice.api.FieldError;
import com.tpe.riskservice.api.InternalApi;
import com.tpe.riskservice.api.ValidationFailedException;
import com.tpe.riskservice.application.assessment.AssessmentService;
import com.tpe.riskservice.application.assessment.StoredAssessment;
import com.tpe.riskservice.domain.rules.PayeeVerification;
import com.tpe.riskservice.domain.rules.PaymentDirection;
import com.tpe.riskservice.domain.rules.PaymentToAssess;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The assessment API, called by payment-orchestrator (specs/14-risk-service.md §3.1). Request and response records
 * are in {@code contracts}. Unauthenticated until M5 (ADR-012).
 */
@InternalApi
@RestController
class AssessmentController {

    private final AssessmentService assessments;

    AssessmentController(AssessmentService assessments) {
        this.assessments = assessments;
    }

    /**
     * {@code 200} with the stored decision. {@code 400 VALIDATION_FAILED} for a missing field, an {@code assessmentId}
     * other than {@code <paymentId>:<direction>}, or an {@code OUTGOING} request without {@code vopResult}; an
     * {@code INCOMING} request's {@code vopResult} is not used.
     */
    @PostMapping("/v1/risk/assessments")
    AssessmentResponse assess(@RequestBody AssessmentRequest request) {
        List<FieldError> errors = new ArrayList<>();
        if (request.assessmentId() == null || request.assessmentId().isBlank()) {
            errors.add(new FieldError("assessmentId", "REQUIRED"));
        } else if (request.paymentId() != null && request.direction() != null
                && !request.assessmentId().equals(AssessmentRequest.assessmentId(request.paymentId(),
                        request.direction()))) {
            errors.add(new FieldError("assessmentId", "INVALID_ASSESSMENT_ID"));
        }
        required("paymentId", request.paymentId(), errors);
        if (request.direction() == null) {
            errors.add(new FieldError("direction", "REQUIRED"));
        } else if (request.direction() == Direction.UNKNOWN) {
            errors.add(new FieldError("direction", "INVALID_DIRECTION"));
        }
        required("amountMinor", request.amount(), errors);
        party("debtor", request.debtor(), errors);
        party("creditor", request.creditor(), errors);
        if (request.direction() == Direction.OUTGOING && request.vopResult() == null) {
            errors.add(new FieldError("vopResult", "REQUIRED"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        PaymentDirection direction = PaymentDirection.valueOf(request.direction().name());
        PaymentToAssess payment = new PaymentToAssess(direction, request.amount(), request.debtor().name(),
                request.debtor().iban(), request.creditor().name(), request.creditor().iban(),
                direction == PaymentDirection.OUTGOING ? PayeeVerification.valueOf(request.vopResult().name()) : null);
        return response(assessments.assess(request.assessmentId(), request.paymentId().value(), payment));
    }

    private static AssessmentResponse response(StoredAssessment stored) {
        return new AssessmentResponse(stored.assessmentId(),
                RiskDecision.valueOf(stored.verdict().decision().name()), stored.verdict().reasonCode(),
                stored.verdict().ruleHits().stream().map(hit -> new RuleHit(hit.ruleId(), hit.score())).toList(),
                stored.assessedAt());
    }

    private static void required(String field, Object value, List<FieldError> errors) {
        if (value == null) {
            errors.add(new FieldError(field, "REQUIRED"));
        }
    }

    private static void party(String field, Party party, List<FieldError> errors) {
        if (party == null) {
            errors.add(new FieldError(field, "REQUIRED"));
            return;
        }
        required(field + ".name", party.name(), errors);
        required(field + ".iban", party.iban(), errors);
    }
}
