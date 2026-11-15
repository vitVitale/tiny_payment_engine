package com.tpe.csmsimulator;

import static com.tpe.csmsimulator.CreditTransfers.CREDITOR;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** The control API's own answers (specs/17-csm-simulator.md §3.5). */
class ControlApiIT extends SimulatorStack {

    @Test
    void counterpartyAccountsAreReplaced() throws Exception {
        var response = send("PUT", "/sim/v1/counterparty-accounts", "application/json",
                "[{\"iban\": \"" + CREDITOR.value() + "\", \"holderName\": \"Erika Musterfrau\"}]");

        assertThat(response.statusCode()).isEqualTo(204);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/sim/v1/scenarios/DE07200200200123456789 | {\"scenario\": \"NOPE\"}",
            "/sim/v1/scenarios/DE07200200200123456789 | {}",
            "/sim/v1/scenarios/NOT-AN-IBAN | {\"scenario\": \"ACCEPT\"}",
            "/sim/v1/counterparty-accounts | [{\"iban\": \"NOT-AN-IBAN\", \"holderName\": \"X\"}]"})
    void aControlRequestItCannotActOnIs400(String path, String body) throws Exception {
        var response = send("PUT", path, "application/json", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"errors\":[{");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"mode\": \"SLOW\"}",
            "{\"mode\": \"SLOW\", \"delayMs\": -1}", "{\"mode\": \"SIDEWAYS\"}"})
    void anOutageItCannotActOnIs400(String body) throws Exception {
        var response = send("POST", "/sim/v1/outage", "application/json", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"");
    }

    @Test
    void receivedNeedsATxId() throws Exception {
        var response = get("/sim/v1/received");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"errors\":[]");
    }

    @Test
    void settlementsSinceAnInstantAreAnEmptyListAtTheStart() throws Exception {
        var response = get("/sim/v1/settlements?since=2026-11-15T10:00:00Z");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[]");
    }
}
