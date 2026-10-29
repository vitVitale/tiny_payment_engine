package com.tpe.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LeaderLeaseTest {

    private long now = 1_000_000_000L;
    private final LeaderLease lease = new LeaderLease(Duration.ofSeconds(2), Duration.ofSeconds(3), () -> now);

    @Test
    void aFreshLeaseLeadsAndIsLive() {
        lease.begin();

        assertThat(lease.isLeader()).isTrue();
        assertThat(lease.isStalled()).isFalse();
        assertThat(lease.isLive()).isTrue();
    }

    @Test
    void theLoopStallsWhenNoIterationCompletedForStallAfter() {
        lease.begin();
        advance(Duration.ofMillis(1_500));
        lease.progress();
        advance(Duration.ofMillis(1_999));

        assertThat(lease.isStalled()).isFalse();

        advance(Duration.ofMillis(2));
        assertThat(lease.isStalled()).isTrue();
        assertThat(lease.isLive()).isFalse();
    }

    @Test
    void aFailedHeartbeatEndsTheLeaseForGood() {
        long term = lease.begin();
        lease.heartbeatFailed(term);
        lease.heartbeatSucceeded(term);

        assertThat(lease.isLeader()).isFalse();
        assertThat(lease.isLive()).isTrue();
    }

    @Test
    void theLeaseExpiresOnceTheLastGoodHeartbeatIsAsOldAsTheSessionTimeout() {
        long term = lease.begin();
        advance(Duration.ofSeconds(1));
        lease.heartbeatSucceeded(term);
        lease.progress();
        advance(Duration.ofMillis(2_999));

        assertThat(lease.isLeader()).isTrue();

        // a frozen JVM wakes up: the server has ended the silent session, whatever the last heartbeat said
        advance(Duration.ofMillis(1));
        assertThat(lease.isLeader()).isFalse();
    }

    @Test
    void aLateHeartbeatOutcomeOfAnEarlierTermIsIgnored() {
        long first = lease.begin();
        lease.end();
        long second = lease.begin();

        lease.heartbeatFailed(first);
        assertThat(lease.revoke(first)).isFalse();

        assertThat(second).isGreaterThan(first);
        assertThat(lease.isLeader()).isTrue();
    }

    @Test
    void aRevokedLeaseStaysNotLiveUntilTheLoopMovesAgain() {
        long term = lease.begin();
        advance(Duration.ofSeconds(3));

        assertThat(lease.revoke(term)).isTrue();
        assertThat(lease.revoke(term)).isFalse();
        assertThat(lease.isLeader()).isFalse();
        assertThat(lease.isLive()).isFalse();

        lease.progress();
        assertThat(lease.isLive()).isTrue();
    }

    @Test
    void aFollowerIsLiveAndDoesNotLead() {
        assertThat(lease.isLeader()).isFalse();
        assertThat(lease.isLive()).isTrue();

        lease.begin();
        advance(Duration.ofSeconds(10));
        lease.end();
        assertThat(lease.isLeader()).isFalse();
        assertThat(lease.isLive()).isTrue();
    }

    private void advance(Duration duration) {
        now += duration.toNanos();
    }
}
