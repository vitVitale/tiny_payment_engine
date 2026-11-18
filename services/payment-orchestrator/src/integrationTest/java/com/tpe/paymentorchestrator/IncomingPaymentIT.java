package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.AnswerSent;
import com.tpe.contracts.AnswerStatus;
import com.tpe.contracts.CreditTransferReceived;
import com.tpe.contracts.DecidedBy;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Party;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentorchestrator.SagaProbe.Row;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An incoming payment up to its answer (specs/11-payment-orchestrator.md §6.2, §6.5, UC-06 2–7, ADR-003, ADR-010):
 * received, risk-assessed, its pending credit created, the verdict out; the state follows the answer the gateway
 * sent, and a rejection cancels the pending credit before {@code PaymentRejected}.
 */
class IncomingPaymentIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(15);
    /** Long enough for the listener, the runner and the sweeper to have acted many times. */
    private static final Duration QUIET = Duration.ofSeconds(1);
    /** The gateway's {@code tpe.csm.answer-deadline-offset}. */
    private static final Duration ANSWER_WINDOW = Duration.ofSeconds(5);
    /** {@code accountId} of {@code create-pending-credit-created.json}. */
    private static final String PAYEE_ACCOUNT = "0192f0e4-7b1c-7cc3-a1b2-0000000000b2";
    private static final Bic PEER = new Bic("PEERDEFFXXX");
    private static final Bic OTHER = new Bic("OTHRDEFFXXX");
    private static final AtomicInteger TRANSFERS = new AtomicInteger();

    @Autowired
    JdbcClient jdbc;

    private SagaProbe probe;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
        riskAnswers("allow");
        ledgerAnswers("create-pending-credit-created", "cancel-pending-credit-cancelled");
    }

    @Test
    void anAllowedTransferIsAcceptedOnceTheAcceptingAnswerIsSent() {
        String txId = txId();

        UUID paymentId = received(PEER, txId, Ibans.own());

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("ACCEPT_REQUESTED", null, null, false)));
        assertThat(probe.verdict(paymentId)).isEqualTo("ACCEPT|-");
        assertThat(probe.csmCommands(txId)).singleElement().satisfies(command -> {
            assertThat(command).startsWith("AcceptCreditTransfer|");
            JsonNode body = JSON.readTree(command.substring(command.indexOf('|') + 1));
            assertThat(body.get("paymentId").asString()).isEqualTo(paymentId.toString());
            assertThat(body.get("debtorAgentBic").asString()).isEqualTo(PEER.value());
        });
        assertThat(probe.riskCalls(paymentId)).singleElement()
                .satisfies(call -> assertThat(call.getBodyAsString()).contains("\"direction\":\"INCOMING\""));
        assertThat(probe.pendingCreditCalls(paymentId)).singleElement().satisfies(call -> {
            assertThat(call.getHeader("Idempotency-Key")).isEqualTo("pending-credit-create:" + paymentId);
            assertThat(JSON.readTree(call.getBodyAsString()).get("reference").get("debtorAgentBic").asString())
                    .isEqualTo(PEER.value());
        });
        assertThat(jdbc.sql("SELECT creditor_account_id::text FROM payments WHERE payment_id = ?").param(paymentId)
                .query(String.class).single()).isEqualTo(PAYEE_ACCOUNT);

        answer(PEER, txId, AnswerStatus.ACCP, null, DecidedBy.VERDICT);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("ACCEPTED", null, null, false)));
        assertThat(probe.transitions(paymentId)).containsExactly("-|RECEIVED|CSM_RECEIVED",
                "RECEIVED|ACCEPT_REQUESTED|PENDING_CREDIT_CREATED", "ACCEPT_REQUESTED|ACCEPTED|ANSWER_ACCP");
        assertThat(probe.finalEvents(paymentId)).as("not before the settlement").isEmpty();
        assertThat(probe.cancelCreditCalls(paymentId)).isEmpty();
    }

    @Test
    void ORC_AC_15_aDeniedTransferIsRejectedByVerdictAndRejectedOnceTheAnswerIsSent() {
        riskAnswers("deny-fr01");
        String txId = txId();

        UUID paymentId = received(PEER, txId, Ibans.own());

        await().atMost(AWAIT).until(() -> probe.verdict(paymentId).equals("REJECT|FR01"));
        assertThat(probe.csmCommands(txId)).singleElement().satisfies(command -> {
            assertThat(command).startsWith("RejectCreditTransfer|");
            assertThat(JSON.readTree(command.substring(command.indexOf('|') + 1)).get("reasonCode").asString())
                    .isEqualTo("FR01");
        });
        await().during(QUIET).atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .as("RECEIVED until the answer is sent").isEqualTo(new Row("RECEIVED", null, null, false)));
        assertThat(probe.pendingCreditCalls(paymentId)).as("no ledger call").isEmpty();

        answer(PEER, txId, AnswerStatus.RJCT, ReasonCode.FR01, DecidedBy.VERDICT);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("REJECTED", "FR01", null, true)));
        assertThat(probe.cancelCreditCalls(paymentId)).as("CANCEL_CREDIT issued").singleElement()
                .satisfies(call -> assertThat(call.getHeader("Idempotency-Key"))
                        .isEqualTo("pending-credit-cancel:" + paymentId));
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|FR01");
        assertThat(JSON.readTree(finalEvent(paymentId)).get("direction").asString()).isEqualTo("INCOMING");
        assertThat(probe.transitions(paymentId)).containsExactly("-|RECEIVED|CSM_RECEIVED",
                "RECEIVED|REJECTED|ANSWER_RJCT");
    }

    @Test
    void ORC_AC_16_theDeadlinesRejectionWhileThePendingCreditIsInFlightDiscardsItAndCancelsTheCredit() {
        String payee = Ibans.own();
        ActionInterference.holdAfter(PendingAction.CREATE_PENDING_CREDIT, payee);
        String txId = txId();

        UUID paymentId = received(PEER, txId, payee);
        await().atMost(AWAIT).until(() -> ActionInterference.held() >= 1);
        assertThat(probe.pendingCreditCalls(paymentId)).as("the ledger created it").hasSize(1);

        answer(PEER, txId, AnswerStatus.RJCT, ReasonCode.AB05, DecidedBy.DEADLINE);
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId).state()).isEqualTo("REJECTED"));
        ActionInterference.releaseHeld();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId))
                .isEqualTo(new Row("REJECTED", "AB05", null, true)));
        await().during(QUIET).atMost(AWAIT).until(() -> ActionInterference.left() >= 1);
        assertThat(probe.csmCommands(txId)).as("the late pending credit is discarded: no verdict").isEmpty();
        assertThat(probe.verdict(paymentId)).isEqualTo("-|-");
        assertThat(probe.cancelCreditCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB05");
        assertThat(probe.transitions(paymentId)).containsExactly("-|RECEIVED|CSM_RECEIVED",
                "RECEIVED|REJECTED|ANSWER_RJCT");
    }

    @Test
    void ORC_AC_26_theSameTxIdFromTwoDebtorAgentsIsTwoPaymentsThatNeverTouchEachOther() {
        String txId = txId();

        UUID fromPeer = received(PEER, txId, Ibans.own());
        UUID fromOther = received(OTHER, txId, Ibans.own());

        assertThat(fromPeer).isNotEqualTo(fromOther);
        await().atMost(AWAIT).untilAsserted(() -> {
            assertThat(probe.row(fromPeer).state()).isEqualTo("ACCEPT_REQUESTED");
            assertThat(probe.row(fromOther).state()).isEqualTo("ACCEPT_REQUESTED");
        });
        assertThat(probe.pendingCreditCalls(fromPeer)).hasSize(1);
        assertThat(probe.pendingCreditCalls(fromOther)).as("a pending credit of its own").hasSize(1);

        answer(OTHER, txId, AnswerStatus.RJCT, ReasonCode.AB05, DecidedBy.DEADLINE);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(fromOther))
                .isEqualTo(new Row("REJECTED", "AB05", null, true)));
        await().during(QUIET).atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(fromPeer))
                .isEqualTo(new Row("ACCEPT_REQUESTED", null, null, false)));
        assertThat(probe.cancelCreditCalls(fromPeer)).isEmpty();

        answer(PEER, txId, AnswerStatus.ACCP, null, DecidedBy.VERDICT);
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(fromPeer).state()).isEqualTo("ACCEPTED"));
        assertThat(probe.row(fromOther).state()).isEqualTo("REJECTED");
    }

    @Test
    void aBlockedPayeeIsRejectedByVerdictAc06() {
        ledgerAnswers("create-pending-credit-blocked-payee", "cancel-pending-credit-cancelled");
        String txId = txId();

        UUID paymentId = received(PEER, txId, Ibans.own());

        await().atMost(AWAIT).until(() -> probe.verdict(paymentId).equals("REJECT|AC06"));
        assertThat(probe.csmCommands(txId)).singleElement().satisfies(command ->
                assertThat(command).startsWith("RejectCreditTransfer|").contains("\"reasonCode\": \"AC06\""));
        assertThat(probe.row(paymentId)).isEqualTo(new Row("RECEIVED", null, null, false));
    }

    @Test
    void aLedgerUnavailableUntilTheGiveUpMarginIsRejectedByVerdictMs03() {
        ledgerAnswers("create-pending-credit-unavailable", "cancel-pending-credit-cancelled");
        String txId = txId();
        Instant receivedAt = CLOCK.instant();

        UUID paymentId = received(PEER, txId, Ibans.own());
        await().atMost(AWAIT).until(() -> probe.pendingCreditCalls(paymentId).size() >= 1);
        // past the first back-off (100 ms), well before the margin
        CLOCK.set(receivedAt.plusMillis(500));
        await().atMost(AWAIT).until(() -> probe.pendingCreditCalls(paymentId).size() >= 2);
        assertThat(probe.verdict(paymentId)).as("retried before the margin").isEqualTo("-|-");

        // the answer deadline less tpe.incoming.verdict-give-up-margin (1 s)
        CLOCK.set(receivedAt.plus(ANSWER_WINDOW).minusSeconds(1));

        await().atMost(AWAIT).until(() -> probe.verdict(paymentId).equals("REJECT|MS03"));
        assertThat(probe.csmCommands(txId)).singleElement().satisfies(command ->
                assertThat(command).startsWith("RejectCreditTransfer|").contains("\"reasonCode\": \"MS03\""));
        assertThat(probe.row(paymentId)).isEqualTo(new Row("RECEIVED", null, null, false));
    }

    @Test
    void aRedeliveredReceiptUnderANewEventIdMakesNoSecondPayment() {
        String txId = txId();
        CreditTransferReceived event = creditTransferReceived(PEER, txId, Ibans.own());
        publishCsmEvent(event, txId, UUID.randomUUID());
        UUID paymentId = await().atMost(AWAIT).until(() -> probe.incoming(PEER.value(), txId),
                java.util.Optional::isPresent).orElseThrow();

        publishCsmEvent(event, txId, UUID.randomUUID());

        await().during(QUIET).atMost(AWAIT).untilAsserted(() -> assertThat(jdbc.sql(
                "SELECT count(*) FROM payments WHERE tx_id = ?").param(txId).query(Long.class).single())
                .isEqualTo(1));
        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId).state())
                .isEqualTo("ACCEPT_REQUESTED"));
        assertThat(probe.pendingCreditCalls(paymentId)).hasSize(1);
    }

    /** {@code CreditTransferReceived} published now; the payment it made. */
    private UUID received(Bic debtorAgent, String txId, String creditorIban) {
        publishCsmEvent(creditTransferReceived(debtorAgent, txId, creditorIban), txId, UUID.randomUUID());
        return await().atMost(AWAIT).until(() -> probe.incoming(debtorAgent.value(), txId),
                java.util.Optional::isPresent).orElseThrow();
    }

    /** As the gateway reports a valid incoming {@code pacs.008} received now, answered by 5 s later. */
    static CreditTransferReceived creditTransferReceived(Bic debtorAgent, String txId, String creditorIban) {
        Instant now = CLOCK.instant();
        return new CreditTransferReceived(new TxId(txId), debtorAgent, Direction.INCOMING,
                new MsgId("M-" + txId), new Uetr(UUID.randomUUID().toString()), new EndToEndId("E2E-" + txId), now,
                now.plus(ANSWER_WINDOW), now.minusMillis(20), LocalDate.ofInstant(now, ZoneOffset.UTC),
                Money.parse("12.34"),
                new Party(new PartyName("Hans Mueller"), new Iban(Ibans.peer()), null),
                new Party(new PartyName("Anna Schmidt"), new Iban(creditorIban), null), new Bic("TPEBDEFFXXX"),
                new RemittanceInformation("Invoice " + txId));
    }

    private void answer(Bic debtorAgent, String txId, AnswerStatus status, StatusReason reason, DecidedBy decidedBy) {
        publishCsmEvent(new AnswerSent(new TxId(txId), debtorAgent, Direction.INCOMING, status, reason, decidedBy,
                CLOCK.instant()), txId, UUID.randomUUID());
    }

    private String finalEvent(UUID paymentId) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE topic = 'payments.events' AND message_key = ?")
                .param(paymentId.toString()).query(String.class).single();
    }

    static String txId() {
        return "PEERIN" + System.nanoTime() % 1_000_000_000L + "X" + TRANSFERS.incrementAndGet();
    }
}
