package com.tpe.riskservice.application.assessment;

import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.riskservice.domain.rules.PaymentDirection;
import com.tpe.riskservice.domain.rules.Verdict;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A row of {@code assessments} (specs/14-risk-service.md §5): the decision of one payment in one direction, as
 * answered and as answered again for a replay. {@link #toString()} leaves out the IBANs (26 §4.4).
 */
public record StoredAssessment(
        String assessmentId,
        UUID paymentId,
        PaymentDirection direction,
        Iban debtorIban,
        Iban creditorIban,
        Money amount,
        Verdict verdict,
        Instant assessedAt) {

    public StoredAssessment {
        Objects.requireNonNull(assessmentId, "assessmentId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(debtorIban, "debtorIban");
        Objects.requireNonNull(creditorIban, "creditorIban");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(assessedAt, "assessedAt");
    }

    @Override
    public String toString() {
        return "StoredAssessment[assessmentId=" + assessmentId + ", decision=" + verdict.decision() + ", assessedAt="
                + assessedAt + "]";
    }
}
