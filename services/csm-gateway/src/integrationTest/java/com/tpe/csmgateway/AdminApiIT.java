package com.tpe.csmgateway;

import static com.tpe.csmgateway.Commands.sendCreditTransfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.SendCreditTransfer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** The read-only admin API for {@code BACKOFFICE_OPERATOR} (specs/15-csm-gateway.md §3.2). */
class AdminApiIT extends GatewayStack {

    private static final Duration HANDLED = Duration.ofSeconds(15);

    @Test
    void theJournalListsEveryMessageOfATransactionWithItsTransmitStatus() {
        SendCreditTransfer command = delivered("TX-ADMIN");

        var response = get("/v1/journal?txId=TX-ADMIN", StaffTokens.token("ops-1", "BACKOFFICE_OPERATOR"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"direction\":\"OUT\"", "\"messageType\":\"pacs.008.001.08\"",
                "\"txId\":\"TX-ADMIN\"", "\"debtorAgentBic\":\"TPEBDEFFXXX\"", "\"valid\":true",
                "\"transmitStatus\":\"DELIVERED\"", command.uetr().value());
    }

    @Test
    void aDebtorAgentBicNarrowsTheJournalToOneTransaction() {
        delivered("TX-NARROW");

        assertThat(get("/v1/journal?txId=TX-NARROW&debtorAgentBic=PEERDEFFXXX",
                StaffTokens.token("ops-1", "BACKOFFICE_OPERATOR")).getBody()).isEqualTo("[]");
    }

    @Test
    void theTransactionsOfATxIdCarryTheirDebtorAgentBic() {
        SendCreditTransfer command = delivered("TX-RECORD");

        var response = get("/v1/transactions/TX-RECORD", StaffTokens.token("ops-1", "BACKOFFICE_OPERATOR"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"direction\":\"OUTGOING\"", "\"debtorAgentBic\":\"TPEBDEFFXXX\"",
                "\"sendStatus\":\"SENT\"", "\"transmitStatus\":\"DELIVERED\"",
                "\"paymentId\":\"" + command.paymentId().value() + "\"");
    }

    @Test
    void anUnknownTransactionIsAnEmptyList() {
        var response = get("/v1/transactions/TX-NONE", StaffTokens.token("ops-1", "BACKOFFICE_OPERATOR"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void withoutATokenItIs401AndWithoutTheRole403() {
        assertThat(get("/v1/journal?txId=TX-1", null).getStatusCode().value()).isEqualTo(401);
        var forbidden = get("/v1/journal?txId=TX-1", StaffTokens.token("ops-2", "SUPPORT_AGENT"));
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(forbidden.getBody()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void theJournalNeedsATxId() {
        var response = get("/v1/journal", StaffTokens.token("ops-1", "BACKOFFICE_OPERATOR"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("\"code\":\"VALIDATION_FAILED\"", "\"errors\":[]");
    }

    private SendCreditTransfer delivered(String txId) {
        SendCreditTransfer command = sendCreditTransfer(txId, START.minusSeconds(1));
        publishCommand(txId, command, UUID.randomUUID());
        await().atMost(HANDLED).until(() -> "DELIVERED".equals(jdbc.sql(
                "SELECT transmit_status FROM outgoing_transfers WHERE tx_id = :txId").param("txId", txId)
                .query(String.class).optional().orElse(null)));
        return command;
    }

    private ResponseEntity<String> get(String path, String token) {
        var request = http().get().uri(path);
        if (token != null) {
            request = request.header("Authorization", bearer(token));
        }
        return request.retrieve().toEntity(String.class);
    }
}
