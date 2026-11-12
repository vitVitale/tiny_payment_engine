package com.tpe.vopservice.application.responding;

import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.application.accounts.AccountHolders;
import com.tpe.vopservice.application.metrics.VopRequestMetrics;
import com.tpe.vopservice.application.metrics.VopRequestMetrics.Cause;
import com.tpe.vopservice.application.metrics.VopRequestMetrics.Role;
import com.tpe.vopservice.application.metrics.VopRequestMetrics.Route;
import com.tpe.vopservice.domain.matching.HolderVerifier;
import com.tpe.vopservice.domain.matching.VerificationAnswer;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Answers verification requests of other PSPs (specs/13-vop-service.md §6.2, UC-03) from the local account-holder
 * projection only, never calling ledger-service, and audits every answer. The requesting service asks it too, for
 * an own customer (13 §6.1 step 2): that answer is not a scheme request and is not audited here.
 */
@Service
public class RespondingService {

    private final BankDirectory directory;
    private final AccountHolders holders;
    private final HolderVerifier verifier;
    private final ResponderAudit audit;
    private final VopRequestMetrics metrics;
    private final Clock clock;

    public RespondingService(BankDirectory directory, AccountHolders holders, HolderVerifier verifier,
            ResponderAudit audit, VopRequestMetrics metrics, Clock clock) {
        this.directory = directory;
        this.holders = holders;
        this.verifier = verifier;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * UC-03 1: the requesting PSP must be a participant of the bank directory, compared by institution. In the MVP it
     * is identified by {@code requestingPspBic}; mTLS in production (13 §3.2).
     *
     * @throws UnknownParticipantException if {@code requestingPspBic} is missing, not a BIC or not listed
     */
    public Bic authenticate(String requestingPspBic) {
        if (requestingPspBic == null) {
            throw new UnknownParticipantException();
        }
        Bic bic;
        try {
            bic = new Bic(requestingPspBic);
        } catch (DomainValidationException e) {
            throw new UnknownParticipantException();
        }
        return directory.lookup(bic).map(bank -> bic).orElseThrow(UnknownParticipantException::new);
    }

    /** UC-03 2–5: look up, verify, audit. */
    public VerificationAnswer respond(SchemeVerification request) {
        VopRequestMetrics.Timing timing = metrics.start();
        VerificationAnswer answer = answer(request.iban(), request.name(), request.organisationId());
        audit.record(new AuditedAnswer(request, answer.result(), clock.instant()));
        timing.stop(Role.RESPONDER, Route.OWN, answer.result(), Cause.NONE);
        return answer;
    }

    /** UC-03 2–4 from the projection, nothing recorded: the answer for {@code payeeName} as holder of {@code iban}. */
    public VerificationAnswer answer(Iban iban, String payeeName, String organisationId) {
        return verifier.verify(holders.findByIban(iban), payeeName, organisationId);
    }
}
