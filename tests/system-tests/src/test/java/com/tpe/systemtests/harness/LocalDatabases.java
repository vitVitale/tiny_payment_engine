package com.tpe.systemtests.harness;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The services' databases on the compose stack, as deploy/postgres/init.sql creates them (local credentials only): what
 * the per-test invariant checks and the debug bundle read (specs/20-testing-strategy.md §2, §3). Read only.
 */
public final class LocalDatabases {

    private static final String POSTGRES = "jdbc:postgresql://localhost:5432/";

    private LocalDatabases() {}

    /** ledger-service's. */
    public static Connection ledger() throws SQLException {
        return DriverManager.getConnection(POSTGRES + "ledger", "ledger", "ledger-local");
    }

    /** csm-gateway's. */
    public static Connection csm() throws SQLException {
        return DriverManager.getConnection(POSTGRES + "csm", "csm", "csm-local");
    }

    /** payment-orchestrator's. */
    public static Connection payments() throws SQLException {
        return DriverManager.getConnection(POSTGRES + "payments", "payments", "payments-local");
    }
}
