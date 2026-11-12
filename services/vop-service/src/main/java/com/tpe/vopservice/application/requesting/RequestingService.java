package com.tpe.vopservice.application.requesting;

import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.UlidGenerator;
import com.tpe.vopservice.application.metrics.VopRequestMetrics;
import com.tpe.vopservice.application.metrics.VopRequestMetrics.Cause;
import com.tpe.vopservice.application.metrics.VopRequestMetrics.Role;
import com.tpe.vopservice.application.metrics.VopRequestMetrics.Route;
import com.tpe.vopservice.application.references.VopResults;
import com.tpe.vopservice.application.responding.RespondingService;
import com.tpe.vopservice.domain.matching.VerificationAnswer;
import com.tpe.vopservice.domain.references.IssuedReference;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Verifies a payee for one of our payers (specs/13-vop-service.md §6.1, UC-02): route by the payee's bank, ask the
 * responder, store the result as a {@code vopReference} valid for {@code reference-validity}. An own customer is
 * answered by the local responding service without any HTTP call ({@code respondedBy} = our BIC); any other bank goes
 * through {@link CounterpartyVop}. Once the request is valid there is always a reference, {@code NOT_POSSIBLE}
 * included (UC-02 3a). A bean of VopConfiguration, which binds {@code tpe.vop.reference-validity}.
 */
public class RequestingService {

    private final BankDirectory directory;
    private final RespondingService responding;
    private final CounterpartyVop counterparty;
    private final VopResults results;
    private final UlidGenerator ulids;
    private final VopRequestMetrics metrics;
    private final Clock clock;
    private final Duration referenceValidity;

    public RequestingService(BankDirectory directory, RespondingService responding, CounterpartyVop counterparty,
            VopResults results, UlidGenerator ulids, VopRequestMetrics metrics, Clock clock,
            Duration referenceValidity) {
        this.directory = directory;
        this.responding = responding;
        this.counterparty = counterparty;
        this.results = results;
        this.ulids = ulids;
        this.metrics = metrics;
        this.clock = clock;
        this.referenceValidity = referenceValidity;
    }

    /** @throws PayeeBankUnknownException if the payee IBAN's bank is not in the directory */
    public IssuedReference verify(PayeeVerification request) {
        BankEntry bank = directory.lookup(request.payeeIban()).orElseThrow(PayeeBankUnknownException::new);
        VopRequestMetrics.Timing timing = metrics.start();
        boolean own = directory.isOwn(request.payeeIban());
        VerificationAnswer answer;
        Cause cause;
        Bic respondedBy;
        if (own) {
            answer = responding.answer(request.payeeIban(), request.payeeName(), request.payeeOrganisationId());
            cause = Cause.NONE;
            respondedBy = directory.ownBic();
        } else {
            CounterpartyVop.Answer remote = counterparty.ask(bank, request.payeeIban(), request.payeeName(),
                    request.payeeOrganisationId());
            answer = remote.answer();
            cause = remote.cause();
            respondedBy = bank.bic();
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        IssuedReference reference = new IssuedReference("VOP-" + ulids.next(), request.debtorIban(),
                request.payeeIban(), request.payeeName(), answer.result(), answer.actualName(), respondedBy, now,
                now.plus(referenceValidity));
        results.save(reference);
        timing.stop(Role.REQUESTER, own ? Route.OWN : Route.REMOTE, answer.result(), cause);
        return reference;
    }
}
