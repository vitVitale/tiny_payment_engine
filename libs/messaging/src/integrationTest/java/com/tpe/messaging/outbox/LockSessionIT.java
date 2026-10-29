package com.tpe.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.messaging.MessagingContainers;
import com.tpe.messaging.Topics;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The relay's lock connection against PostgreSQL: a server-side lease (ADR-006 amendment). */
class LockSessionIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final DataSource dataSource = new DriverManagerDataSource(MessagingContainers.POSTGRES.getJdbcUrl(),
            MessagingContainers.POSTGRES.getUsername(), MessagingContainers.POSTGRES.getPassword());
    private final String lock = "lock-it-" + UUID.randomUUID();
    private final List<LockSession> sessions = new ArrayList<>();

    @AfterEach
    void close() {
        sessions.forEach(LockSession::close);
    }

    @Test
    void aSilentSessionIsEndedByTheServerAndLosesTheLock() throws SQLException {
        LockSession leader = open();
        LockSession other = open();
        assertThat(leader.tryLock(lock)).isTrue();
        assertThat(other.tryLock(lock)).isFalse();

        Topics.await(Duration.ofSeconds(5), () -> tryLock(other));

        assertThat(alive(leader)).isFalse();
    }

    @Test
    void heartbeatsKeepTheLock() throws SQLException {
        LockSession leader = open();
        assertThat(leader.tryLock(lock)).isTrue();

        for (int i = 0; i < 8; i++) {
            pause(Duration.ofMillis(300));
            assertThat(alive(leader)).isTrue();
        }

        // opened only now: a silent session of its own would be ended too
        assertThat(open().tryLock(lock)).isFalse();
    }

    @Test
    void abortFromAnotherThreadReleasesTheLockAtOnce() throws Exception {
        LockSession leader = open();
        LockSession other = open();
        assertThat(leader.tryLock(lock)).isTrue();

        Thread.ofPlatform().start(leader::abort).join();

        assertThat(other.tryLock(lock)).isTrue();
        assertThat(alive(leader)).isFalse();
    }

    private LockSession open() throws SQLException {
        LockSession session = LockSession.open(dataSource, TIMEOUT);
        sessions.add(session);
        return session;
    }

    private boolean tryLock(LockSession session) {
        try {
            return session.tryLock(lock);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean alive(LockSession session) {
        try {
            session.heartbeat();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
