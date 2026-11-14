package com.tpe.paymentapi.application.status;

import com.tpe.contracts.Direction;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.paymentapi.application.submission.Failure;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentapi.application.submission.OrchestratorUnavailableException;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import com.tpe.paymentdomain.PaymentId;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A payment as payment-orchestrator has it now (specs/10-payment-api.md §3.2, §4, UC-07 2): the status read, a read,
 * retried once on a connect failure, a timeout or a gateway status (specs/00-engineering-baseline.md §8).
 */
public class PaymentStatuses {

    private static final Set<Failure> RETRIED = Set.of(Failure.CONNECT, Failure.TIMEOUT, Failure.GATEWAY);

    private static final Logger LOG = LoggerFactory.getLogger(PaymentStatuses.class);

    private final Orchestrator orchestrator;

    public PaymentStatuses(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * What the orchestrator answered, after the retry.
     *
     * @throws UnexpectedAnswerException an answer outside the contract
     */
    public Orchestrator.Read read(PaymentId paymentId) {
        Orchestrator.Read read = orchestrator.payment(paymentId);
        if (read instanceof Orchestrator.Unavailable unavailable && RETRIED.contains(unavailable.failure())) {
            LOG.info("retrying the status read of payment {} after {}", paymentId.value(), unavailable.failure());
            read = orchestrator.payment(paymentId);
        }
        return read;
    }

    /**
     * An outgoing payment; only those are visible through this API (§3.2).
     *
     * @throws PaymentNotFoundException unknown, or an incoming payment
     * @throws OrchestratorUnavailableException no answer from the orchestrator
     * @throws UnexpectedAnswerException an answer outside the contract
     */
    public PaymentView outgoing(PaymentId paymentId) {
        return switch (read(paymentId)) {
            case Orchestrator.Found found when found.payment().direction() == Direction.OUTGOING -> found.payment();
            case Orchestrator.Found incoming -> throw new PaymentNotFoundException();
            case Orchestrator.NotFound notFound -> throw new PaymentNotFoundException();
            case Orchestrator.Unavailable unavailable -> {
                LOG.warn("orchestrator unavailable for the status of payment {}: {} {}", paymentId.value(),
                        unavailable.failure(), unavailable.why());
                throw new OrchestratorUnavailableException(unavailable.why());
            }
        };
    }
}
