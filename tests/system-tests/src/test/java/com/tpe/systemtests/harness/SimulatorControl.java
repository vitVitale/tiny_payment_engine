package com.tpe.systemtests.harness;

import com.tpe.invariantchecker.CsmInvariants.Settlement;
import com.tpe.invariantchecker.CsmInvariants.SimulatorMessage;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * csm-simulator's control API (specs/17-csm-simulator.md §3.5), for test use: the peer banks' accounts, the scenario of
 * a creditor, and the CSM's received and settlement logs. The simulator is shared by every test of the JVM, so the
 * accounts are kept here and {@code PUT} as a whole, each test adding its own; scenarios are per creditor IBAN, and
 * every test uses its own payees.
 */
public final class SimulatorControl {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI baseUrl;
    private final Map<String, String> accounts = new LinkedHashMap<>();

    public SimulatorControl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Clears all of the simulator's state (17 §3.5): once at suite start, never during a run (20 §2). */
    public synchronized void reset() {
        accounts.clear();
        send("POST", "/sim/v1/reset", null, 204);
    }

    /** A holder of {@code iban} at a peer bank: the simulator's VoP responder answers for it (17 §3.3). */
    public synchronized void counterpartyAccount(String iban, String holderName) {
        accounts.put(iban, holderName);
        var body = new ArrayList<Map<String, String>>();
        accounts.forEach((account, holder) -> body.add(Map.of("iban", account, "holderName", holder)));
        send("PUT", "/sim/v1/counterparty-accounts", body, 204);
    }

    /** The CSM's behaviour for payments to {@code creditorIban}, e.g. {@code ACCEPT} or {@code REJECT:AC04}. */
    public void scenario(String creditorIban, String scenario) {
        send("PUT", "/sim/v1/scenarios/" + creditorIban, Map.of("scenario", scenario), 204);
    }

    /** Every message the simulator received or sent for {@code txId}, oldest first. */
    public List<SimulatorMessage> received(String txId) {
        var messages = new ArrayList<SimulatorMessage>();
        for (JsonNode message : get("/sim/v1/received?txId=" + URLEncoder.encode(txId, StandardCharsets.UTF_8))) {
            messages.add(new SimulatorMessage(txId, message.get("kind").asString(),
                    message.get("messageType").asString(), Instant.parse(message.get("at").asString())));
        }
        return messages;
    }

    /** The CSM's settlement log since {@code since}, inclusive, oldest first. */
    public List<Settlement> settlements(Instant since) {
        var settlements = new ArrayList<Settlement>();
        for (JsonNode entry : get("/sim/v1/settlements?since=" + URLEncoder.encode(since.toString(),
                StandardCharsets.UTF_8))) {
            settlements.add(new Settlement(entry.get("txId").asString(), entry.get("debtorAgentBic").asString(),
                    entry.get("direction").asString(), entry.get("amountMinor").asLong(),
                    Instant.parse(entry.get("settledAt").asString())));
        }
        return settlements;
    }

    /**
     * The envelopes of {@code messageType} (e.g. {@code pacs.002.001.10}) the simulator sent for {@code txId}, oldest
     * first, as XML.
     */
    public List<String> sentEnvelopes(String txId, String messageType) {
        var envelopes = new ArrayList<String>();
        for (JsonNode message : get("/sim/v1/received?txId=" + URLEncoder.encode(txId, StandardCharsets.UTF_8))) {
            if (message.get("kind").asString().equals("SENT")
                    && message.get("messageType").asString().equals(messageType)) {
                envelopes.add(message.get("envelope").asString());
            }
        }
        return envelopes;
    }

    /**
     * The CSM transport's state for every transfer (17 §3.5): {@code DOWN}, {@code RESET} or {@code UP}. Shared by every
     * test of the JVM, so a test that takes it down brings it {@code UP} again however it ends.
     */
    public void outage(String mode) {
        send("POST", "/sim/v1/outage", Map.of("mode", mode), 204);
    }

    /**
     * Sends {@code xml} to csm-gateway as the CSM would, as it is ({@code POST /sim/v1/incoming-payments/raw}); the
     * HTTP status the gateway answered.
     */
    public int sendRaw(String xml) {
        var request = HttpRequest.newBuilder(baseUrl.resolve("/sim/v1/incoming-payments/raw"))
                .header("Content-Type", "application/xml").POST(HttpRequest.BodyPublishers.ofString(xml)).build();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("POST " + request.uri() + " answered " + response.statusCode() + ": "
                        + response.body());
            }
            return JSON.readTree(response.body()).get("gatewayStatus").asInt();
        } catch (IOException e) {
            throw new IllegalStateException("POST " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** {@code GET /sim/v1/received?txId=} as the simulator answers it, for the debug bundle. */
    public String receivedLog(String txId) {
        return get("/sim/v1/received?txId=" + URLEncoder.encode(txId, StandardCharsets.UTF_8)).toString();
    }

    private JsonNode get(String path) {
        return JSON.readTree(send("GET", path, null, 200));
    }

    private String send(String method, String path, Object body, int expected) {
        var request = HttpRequest.newBuilder(baseUrl.resolve(path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expected) {
                throw new IllegalStateException(method + " " + request.uri() + " answered " + response.statusCode()
                        + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
