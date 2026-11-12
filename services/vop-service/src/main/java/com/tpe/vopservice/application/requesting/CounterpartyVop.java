package com.tpe.vopservice.application.requesting;

import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.application.metrics.VopRequestMetrics.Cause;
import com.tpe.vopservice.domain.matching.VerificationAnswer;
import java.util.Objects;

/**
 * Asks the responding PSP of another bank (specs/13-vop-service.md §6.1 step 3, UC-02 3): the only way out of
 * vop-service. Never throws for a counterparty's failure: a timeout, connect error, {@code 5xx} or invalid response is
 * {@code NOT_POSSIBLE} with cause {@code EXTERNAL} (UC-02 3a).
 */
public interface CounterpartyVop {

    Answer ask(BankEntry bank, Iban payeeIban, String payeeName, String payeeOrganisationId);

    /** The counterparty's answer, and the cause when it is {@code NOT_POSSIBLE} ({@code NONE} otherwise). */
    record Answer(VerificationAnswer answer, Cause cause) {

        public Answer {
            Objects.requireNonNull(answer, "answer");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
