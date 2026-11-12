package com.tpe.vopservice.infrastructure.peer;

import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.application.metrics.VopRequestMetrics.Cause;
import com.tpe.vopservice.application.requesting.CounterpartyVop;
import com.tpe.vopservice.domain.matching.VerificationAnswer;
import com.tpe.vopservice.domain.matching.VerificationResult;
import org.springframework.stereotype.Component;

/**
 * Until the VoP remote track adds the HTTP client of specs/13-vop-service.md §6.1 step 3, a counterparty cannot be
 * asked: {@code NOT_POSSIBLE}, the "responder unreachable" result of UC-02 3a. The cause is ours ({@code LOCAL}),
 * so SLO-VOP-03 shows the gap. The client replaces this adapter.
 */
@Component
class NoCounterpartyVop implements CounterpartyVop {

    @Override
    public Answer ask(BankEntry bank, Iban payeeIban, String payeeName, String payeeOrganisationId) {
        return new Answer(new VerificationAnswer(VerificationResult.NOT_POSSIBLE, null), Cause.LOCAL);
    }
}
