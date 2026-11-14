package com.tpe.paymentapi.application.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.contracts.vop.VopReferenceProblem;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentapi.application.validation.CreditorAgentNotReachableException;
import com.tpe.paymentapi.application.validation.ValidatedPayment;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** specs/10-payment-api.md §3.1 steps 4–7, §4, §5.3: the reference check, the command and the retry rules. */
class PaymentSubmissionTest {

    private static final String CLIENT = "mobile-app";
    private static final String KEY = "a1b2c3";
    private static final String REFERENCE = "VOP-01JABCDEFGHJKMNPQRSTVWXYZ0";
    private static final ValidatedPayment PAYMENT = new ValidatedPayment(new EndToEndId("INV-2026-0042"),
            new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"), new PartyName("Max Mustermann"),
            new Iban("DE63200200200000000001"), new Bic("PEERDEFFXXX"), new Money(12_345, Currency.getInstance("EUR")),
            new RemittanceInformation("Invoice 42"), REFERENCE);
    private static final PaymentAccepted ACCEPTED = new PaymentAccepted(
            new PaymentId(UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-000000000001")), PaymentState.RECEIVED,
            new TxId("TPE01JABCDEFGHJKMNPQRST0001"), Instant.parse("2026-11-14T10:00:00Z"));

    private final FakeChecks checks = new FakeChecks();
    private final FakeOrchestrator orchestrator = new FakeOrchestrator();
    private final PaymentSubmission submission = new PaymentSubmission(checks, orchestrator);

    @Test
    void aValidReferenceSubmitsTheCommandWithTheCheckedResult() {
        checks.answers.add(new ReferenceChecks.Valid(VopResult.CLOSE_MATCH));
        orchestrator.answers.add(new Orchestrator.Accepted(ACCEPTED, true));

        assertThat(submission.submit(CLIENT, KEY, PAYMENT)).isEqualTo(ACCEPTED);

        assertThat(checks.asked).containsExactly(REFERENCE);
        assertThat(orchestrator.submitted).containsExactly(new SubmitPayment(
                CommandId.derive(CLIENT, KEY).value(), CLIENT, PAYMENT.endToEndId(),
                new SubmitPayment.Debtor(PAYMENT.debtorName(), PAYMENT.debtorIban()),
                new SubmitPayment.Creditor(PAYMENT.creditorName(), PAYMENT.creditorIban(), PAYMENT.creditorBic()),
                PAYMENT.amount(), PAYMENT.remittanceInformation(), REFERENCE, VopResult.CLOSE_MATCH));
    }

    @Test
    void thePaymentOfAnEarlierSubmitIsAcceptedAlike() {
        checks.answers.add(new ReferenceChecks.Valid(VopResult.MATCH));
        orchestrator.answers.add(new Orchestrator.Accepted(ACCEPTED, false));

        assertThat(submission.submit(CLIENT, KEY, PAYMENT)).isEqualTo(ACCEPTED);
    }

    @ParameterizedTest
    @EnumSource(VopReferenceProblem.class)
    void anInvalidReferenceIsVopRequiredWithItsProblem(VopReferenceProblem problem) {
        checks.answers.add(new ReferenceChecks.Invalid(problem));

        assertThatThrownBy(() -> submission.submit(CLIENT, KEY, PAYMENT))
                .isInstanceOfSatisfying(VopRequiredException.class,
                        e -> assertThat(e.problem()).isEqualTo(problem.name()));
        assertThat(orchestrator.submitted).isEmpty();
    }

    @Test
    void aMissingReferenceIsVopRequiredWithoutAsking() {
        ValidatedPayment without = new ValidatedPayment(PAYMENT.endToEndId(), PAYMENT.debtorName(),
                PAYMENT.debtorIban(), PAYMENT.creditorName(), PAYMENT.creditorIban(), null, PAYMENT.amount(), null,
                null);

        assertThatThrownBy(() -> submission.submit(CLIENT, KEY, without))
                .isInstanceOfSatisfying(VopRequiredException.class,
                        e -> assertThat(e.problem()).isEqualTo("MISSING"));
        assertThat(checks.asked).isEmpty();
    }

    /** A read: retried on what specs/00-engineering-baseline.md §8 allows. */
    @ParameterizedTest
    @EnumSource(value = Failure.class, names = {"CONNECT", "TIMEOUT", "GATEWAY"})
    void theReferenceCheckIsRetriedOnce(Failure failure) {
        checks.answers.add(new ReferenceChecks.Unavailable(failure, "down"));
        checks.answers.add(new ReferenceChecks.Valid(VopResult.MATCH));
        orchestrator.answers.add(new Orchestrator.Accepted(ACCEPTED, true));

        assertThat(submission.submit(CLIENT, KEY, PAYMENT)).isEqualTo(ACCEPTED);
        assertThat(checks.asked).hasSize(2);
    }

    @Test
    void aReferenceCheckFailingWithAnInternalErrorIsNotRetried() {
        checks.answers.add(new ReferenceChecks.Unavailable(Failure.SERVER_ERROR, "500"));

        assertThatThrownBy(() -> submission.submit(CLIENT, KEY, PAYMENT)).isInstanceOf(VopUnavailableException.class);
        assertThat(checks.asked).hasSize(1);
    }

    @Test
    void aReferenceCheckFailingTwiceIsVopUnavailable() {
        checks.answers.add(new ReferenceChecks.Unavailable(Failure.TIMEOUT, "slow"));
        checks.answers.add(new ReferenceChecks.Unavailable(Failure.CONNECT, "down"));

        assertThatThrownBy(() -> submission.submit(CLIENT, KEY, PAYMENT)).isInstanceOf(VopUnavailableException.class);
        assertThat(orchestrator.submitted).isEmpty();
    }

    @Test
    void aSubmitThatDidNotConnectIsRetriedOnceWithTheSameCommand() {
        checks.answers.add(new ReferenceChecks.Valid(VopResult.MATCH));
        orchestrator.answers.add(new Orchestrator.Unavailable(Failure.CONNECT, "refused"));
        orchestrator.answers.add(new Orchestrator.Accepted(ACCEPTED, true));

        assertThat(submission.submit(CLIENT, KEY, PAYMENT)).isEqualTo(ACCEPTED);
        assertThat(orchestrator.submitted).hasSize(2).containsOnly(orchestrator.submitted.getFirst());
    }

    @Test
    void aSubmitThatDidNotConnectTwiceIsOrchestratorUnavailable() {
        checks.answers.add(new ReferenceChecks.Valid(VopResult.MATCH));
        orchestrator.answers.add(new Orchestrator.Unavailable(Failure.CONNECT, "refused"));
        orchestrator.answers.add(new Orchestrator.Unavailable(Failure.CONNECT, "refused"));

        assertThatThrownBy(() -> submission.submit(CLIENT, KEY, PAYMENT))
                .isInstanceOf(OrchestratorUnavailableException.class);
        assertThat(orchestrator.submitted).hasSize(2);
    }

    /** It may have reached the orchestrator: the client's retry with the same key finds the payment (UC-01 6a). */
    @ParameterizedTest
    @EnumSource(value = Failure.class, names = "CONNECT", mode = EnumSource.Mode.EXCLUDE)
    void aSubmitThatMayHaveArrivedIsNotRetried(Failure failure) {
        checks.answers.add(new ReferenceChecks.Valid(VopResult.MATCH));
        orchestrator.answers.add(new Orchestrator.Unavailable(failure, "no answer"));

        assertThatThrownBy(() -> submission.submit(CLIENT, KEY, PAYMENT))
                .isInstanceOf(OrchestratorUnavailableException.class);
        assertThat(orchestrator.submitted).hasSize(1);
    }

    @Test
    void aCommandConflictIsAReusedIdempotencyKey() {
        checks.answers.add(new ReferenceChecks.Valid(VopResult.MATCH));
        orchestrator.answers.add(new Orchestrator.CommandConflict());

        assertThatThrownBy(() -> submission.submit(CLIENT, KEY, PAYMENT))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void theOrchestratorRefusingTheCreditorBankIsNotReachable() {
        checks.answers.add(new ReferenceChecks.Valid(VopResult.MATCH));
        orchestrator.answers.add(new Orchestrator.CreditorAgentNotReachable());

        assertThatThrownBy(() -> submission.submit(CLIENT, KEY, PAYMENT))
                .isInstanceOf(CreditorAgentNotReachableException.class);
    }

    private static final class FakeChecks implements ReferenceChecks {

        final Deque<Answer> answers = new ArrayDeque<>();
        final List<String> asked = new ArrayList<>();

        @Override
        public Answer check(String vopReference, Iban debtorIban, Iban payeeIban, PartyName payeeName) {
            assertThat(debtorIban).isEqualTo(PAYMENT.debtorIban());
            assertThat(payeeIban).isEqualTo(PAYMENT.creditorIban());
            assertThat(payeeName).isEqualTo(PAYMENT.creditorName());
            asked.add(vopReference);
            return answers.removeFirst();
        }
    }

    private static final class FakeOrchestrator implements Orchestrator {

        final Deque<Answer> answers = new ArrayDeque<>();
        final List<SubmitPayment> submitted = new ArrayList<>();

        @Override
        public Answer submit(SubmitPayment command) {
            submitted.add(command);
            return answers.removeFirst();
        }
    }
}
