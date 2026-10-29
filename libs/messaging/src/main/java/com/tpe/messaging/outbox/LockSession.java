package com.tpe.messaging.outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The relay's dedicated lock connection (01 §3.3, ADR-006 amendment). It runs {@code idle_session_timeout}, so
 * PostgreSQL ends the session, and frees the advisory lock, once the holder goes silent for the timeout, whether its
 * node died, its network is cut or its JVM froze. Reads on it time out after the same duration, so a blackholed
 * connection fails instead of hanging. Used by the publish loop and the lease thread at once: the driver serialises
 * statements, and {@link #abort} works from any thread.
 */
final class LockSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LockSession.class);

    private final Connection connection;

    private LockSession(Connection connection) {
        this.connection = connection;
    }

    /** A new connection from {@code connections}, which must be unpooled (01 §3.3). */
    static LockSession open(DataSource connections, Duration sessionTimeout) throws SQLException {
        Connection connection = connections.getConnection();
        try {
            connection.setAutoCommit(true);
            connection.setNetworkTimeout(Runnable::run, Math.toIntExact(sessionTimeout.toMillis()));
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET idle_session_timeout = " + sessionTimeout.toMillis());
            }
            return new LockSession(connection);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** {@code pg_try_advisory_lock(hashtext(name))}. */
    boolean tryLock(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** {@code SELECT 1}; throws if the session, and with it the lock, is gone. */
    void heartbeat() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
    }

    void listen(String channel) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + channel);
        }
    }

    /** Waits up to {@code timeout} for notifications on the channels listened to. */
    void awaitNotifications(Duration timeout) throws SQLException {
        connection.unwrap(PGConnection.class).getNotifications(Math.toIntExact(timeout.toMillis()));
    }

    /** Closes the socket without waiting for a running statement: the server ends the session and frees the lock. */
    void abort() {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException e) {
            log.debug("Outbox relay could not abort its lock connection", e);
        }
    }

    /** Releases the lock and the listener, even if the connection is pooled after all, and closes it. */
    @Override
    public void close() {
        try {
            if (!connection.isClosed()) {
                try (PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock_all()");
                        Statement unlisten = connection.createStatement()) {
                    unlock.execute();
                    unlisten.execute("UNLISTEN *");
                }
            }
        } catch (SQLException e) {
            log.debug("Outbox relay could not release its lock connection cleanly", e);
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Outbox relay could not close its lock connection", e);
            }
        }
    }
}
