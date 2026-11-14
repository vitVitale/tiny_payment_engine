package com.tpe.paymentapi.application.submission;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentapi.application.validation.CreditorAgentNotReachableException;
import com.tpe.paymentapi.application.validation.ValidatedPayment;
import com.tpe.paymentdomain.CommandId;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The payment submission service (specs/10-payment-api.md §3.1 steps 4–7): checks the VoP reference (§5.3), derives
 * the {@code commandId} and submits the payment to the orchestrator with the check's result as {@code vopResult}.
 * Retries are explicit here (specs/00-engineering-baseline.md §8, 10 §4): the check, a read, once on a connect
 * failure, a timeout or a gateway status; the submit once on a connect failure only. A submit that may have arrived
 * is not repeated here; the client's retry with the same key carries the same {@code commandId} (UC-01 5a, 6a).
 */
public class PaymentSubmission {

    /** The {@code problem} of a {@code 422 VOP_REQUIRED} for a payment without a reference (UC-01 4a). */
    static final String MISSING = "MISSING";

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSubmission.class);
    private static final Set<Failure> CHECK_RETRIED = Set.of(Failure.CONNECT, Failure.TIMEOUT, Failure.GATEWAY);

    private final ReferenceChecks checks;
    private final Orchestrator orchestrator;

    public PaymentSubmission(ReferenceChecks checks, Orchestrator orchestrator) {
        this.checks = checks;
        this.orchestrator = orchestrator;
    }

    /**
     * @param clientId the token's {@code client_id}
     * @throws VopRequiredException the reference is missing or does not cover the payment
     * @throws VopUnavailableException vop-service could not check it
     * @throws IdempotencyKeyReusedException the key's {@code commandId} has a payment for another request
     * @throws CreditorAgentNotReachableException the orchestrator refuses the creditor bank
     * @throws OrchestratorUnavailableException no answer from the orchestrator
     */
    public PaymentAccepted submit(String clientId, String idempotencyKey, ValidatedPayment payment) {
        VopResult vopResult = checkReference(payment);
        SubmitPayment command = new SubmitPayment(CommandId.derive(clientId, idempotencyKey).value(), clientId,
                payment.endToEndId(), new SubmitPayment.Debtor(payment.debtorName(), payment.debtorIban()),
                new SubmitPayment.Creditor(payment.creditorName(), payment.creditorIban(), payment.creditorBic()),
                payment.amount(), payment.remittanceInformation(), payment.vopReference(), vopResult);
        Orchestrator.Answer answer = orchestrator.submit(command);
        if (answer instanceof Orchestrator.Unavailable unavailable && unavailable.failure() == Failure.CONNECT) {
            answer = orchestrator.submit(command);
        }
        return switch (answer) {
            case Orchestrator.Accepted accepted -> accepted.payment();
            case Orchestrator.CommandConflict conflict -> throw new IdempotencyKeyReusedException(
                    "commandId " + command.commandId() + " has a payment for another request");
            case Orchestrator.CreditorAgentNotReachable refused -> throw new CreditorAgentNotReachableException(
                    "the orchestrator refuses creditor bank " + payment.creditorIban().bankCode());
            case Orchestrator.Unavailable unavailable -> {
                LOG.warn("orchestrator unavailable for commandId {}: {} {}", command.commandId(),
                        unavailable.failure(), unavailable.why());
                throw new OrchestratorUnavailableException(unavailable.why());
            }
        };
    }

    private VopResult checkReference(ValidatedPayment payment) {
        if (payment.vopReference() == null) {
            throw new VopRequiredException(MISSING);
        }
        ReferenceChecks.Answer answer = check(payment);
        if (answer instanceof ReferenceChecks.Unavailable unavailable && CHECK_RETRIED.contains(unavailable.failure())) {
            answer = check(payment);
        }
        return switch (answer) {
            case ReferenceChecks.Valid valid -> valid.result();
            case ReferenceChecks.Invalid invalid -> throw new VopRequiredException(invalid.problem().name());
            case ReferenceChecks.Unavailable unavailable -> {
                LOG.warn("vop-service unavailable for a reference check: {} {}", unavailable.failure(),
                        unavailable.why());
                throw new VopUnavailableException(unavailable.why());
            }
        };
    }

    private ReferenceChecks.Answer check(ValidatedPayment payment) {
        return checks.check(payment.vopReference(), payment.debtorIban(), payment.creditorIban(),
                payment.creditorName());
    }
}
