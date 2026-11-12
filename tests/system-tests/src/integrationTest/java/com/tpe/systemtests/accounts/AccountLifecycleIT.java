package com.tpe.systemtests.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.AccountStatus;
import com.tpe.contracts.BlockReason;
import com.tpe.paymentdomain.UuidV7Generator;
import com.tpe.systemtests.harness.AccountsProjection.Received;
import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.LedgerSystemTest;
import com.tpe.systemtests.harness.MockIssuer;
import com.tpe.systemtests.harness.TestData;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * UC-10 end to end (specs/20-testing-strategy.md §4): an account's lifecycle against the running ledger-service, as
 * the vop / risk projections of {@code accounts.events} see it — here the harness's test projection (ADR-007).
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class AccountLifecycleIT extends LedgerSystemTest {

    private static final long FUNDED_MINOR = 25_000;
    private static final UuidV7Generator PAYMENT_IDS =
            new UuidV7Generator(Clock.systemUTC(), new SecureRandom());

    private final String operator = MockIssuer.staff("systest-operator", "BACKOFFICE_OPERATOR");
    private final String compliance = MockIssuer.staff("systest-compliance", "COMPLIANCE_OFFICER");
    private final String riskService = MockIssuer.riskService();

    @Test
    void E2E_A_01_openFundBlockUnblockCloseReachesTheProjectionAndCloseIsRefusedWhileNotEmpty() {
        var opened = open(operator, TestData.newCustomer());
        assertThat(opened.status()).isEqualTo(201);
        UUID account = UUID.fromString(opened.body().get("accountId").asString());
        String iban = opened.body().get("iban").asString();
        awaitProjected(account, 1, AccountStatus.OPEN);

        assertThat(ledger().fund(operator, account, FUNDED_MINOR).status()).isEqualTo(200);

        assertThat(ledger().block(operator, account, BlockReason.OPERATOR).status()).isEqualTo(200);
        awaitProjected(account, 2, AccountStatus.BLOCKED);
        assertThat(ledger().unblock(operator, account, BlockReason.OPERATOR).status()).isEqualTo(200);
        awaitProjected(account, 3, AccountStatus.OPEN);

        // a sanctions block, as risk-service sets it: an operator cannot lift it, compliance can (12 §3.2)
        assertThat(ledger().block(riskService, account, BlockReason.SANCTIONS).status()).isEqualTo(200);
        awaitProjected(account, 4, AccountStatus.BLOCKED);
        var liftedByOperator = ledger().unblock(operator, account, BlockReason.SANCTIONS);
        assertThat(liftedByOperator.status()).isEqualTo(403);
        assertThat(liftedByOperator.code()).isEqualTo("SANCTIONS_BLOCK_PROTECTED");
        assertThat(accountStatus(account)).isEqualTo("BLOCKED");
        assertThat(ledger().unblock(compliance, account, BlockReason.SANCTIONS).status()).isEqualTo(200);
        awaitProjected(account, 5, AccountStatus.OPEN);

        var notEmpty = ledger().close(operator, account);
        assertThat(notEmpty.status()).isEqualTo(422);
        assertThat(notEmpty.code()).isEqualTo("ACCOUNT_NOT_EMPTY");
        assertThat(accountStatus(account)).isEqualTo("OPEN");

        payOut(iban, FUNDED_MINOR);
        assertThat(ledger().account(operator, account).body().get("bookedBalanceMinor").asLong()).isZero();

        var closed = ledger().close(operator, account);
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.body().get("status").asString()).isEqualTo("CLOSED");
        awaitProjected(account, 6, AccountStatus.CLOSED);

        // every lifecycle change arrived, in revision order; a redelivery may repeat one, never reorder (01 §3.3)
        List<Received> received = PROJECTION.received(account);
        assertThat(received).extracting(Received::revision).isSorted();
        assertThat(received.stream().distinct().toList()).containsExactly(
                new Received("AccountOpened", 1), new Received("AccountBlocked", 2),
                new Received("AccountUnblocked", 3), new Received("AccountBlocked", 4),
                new Received("AccountUnblocked", 5), new Received("AccountClosed", 6));
    }

    /**
     * The money leaves as an outgoing payment would: a hold over the whole balance, settled to the CSM account. The
     * hold id is the payment's id, a UUID v7 (specs/00-engineering-baseline.md §4).
     */
    private void payOut(String iban, long amountMinor) {
        UUID hold = PAYMENT_IDS.next();
        var created = post(null, "/v1/holds", Map.of("holdId", hold, "debtorIban", iban, "amountMinor", amountMinor,
                "currency", "EUR", "reference", Map.of("paymentId", hold)));
        assertThat(created.status()).isEqualTo(201);
        assertThat(post(null, "/v1/holds/" + hold + "/settle", Map.of()).status()).isEqualTo(200);
    }

    private void awaitProjected(UUID account, long revision, AccountStatus status) {
        await().atMost(Bounds.eventDelivery()).untilAsserted(() ->
                assertThat(PROJECTION.account(account)).hasValueSatisfying(projected -> {
                    assertThat(projected.revision()).isEqualTo(revision);
                    assertThat(projected.snapshot().status()).isEqualTo(status);
                }));
    }

    private String accountStatus(UUID account) {
        return ledger().account(operator, account).body().get("status").asString();
    }
}
