package com.tpe.vopservice.application.references;

import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.domain.references.ReferenceCheck;
import com.tpe.vopservice.domain.references.ReferenceProblem;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Checks a {@code vopReference} for payment-api (specs/13-vop-service.md §3.1, UC-01 4): one primary-key read, so it
 * fits the 50 ms of the initiation budget (§8).
 */
@Service
public class ReferenceChecking {

    private final VopResults results;
    private final Clock clock;

    public ReferenceChecking(VopResults results, Clock clock) {
        this.results = results;
        this.clock = clock;
    }

    public ReferenceCheck check(String vopReference, Iban debtorIban, Iban payeeIban, String payeeName) {
        return results.find(vopReference)
                .map(reference -> reference.check(debtorIban, payeeIban, payeeName, clock.instant()))
                .orElseGet(() -> ReferenceCheck.invalid(ReferenceProblem.UNKNOWN));
    }
}
