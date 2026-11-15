package com.tpe.csmsimulator.api.control;

import com.tpe.csmsimulator.api.InvalidControlRequestException;
import com.tpe.csmsimulator.application.CsmSimulation;
import com.tpe.csmsimulator.application.JournalEntry;
import com.tpe.csmsimulator.application.Outage;
import com.tpe.csmsimulator.domain.scenarios.CounterpartyAccount;
import com.tpe.csmsimulator.domain.scenarios.Scenario;
import com.tpe.csmsimulator.domain.scheme.Settlement;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.TxId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The control API for tests, {@code /sim/v1} (specs/17-csm-simulator.md §3.5). */
@RestController
@RequestMapping("/sim/v1")
class ControlController {

    record AccountBody(String iban, String holderName) {}

    record ScenarioBody(String scenario) {}

    /** @param delayMs for {@code SLOW} only */
    record OutageBody(Outage.Mode mode, Long delayMs) {}

    /** @param kind {@code RECEIVED} or {@code SENT} */
    record Message(String kind, String messageType, String msgId, Instant at, boolean duplicate, String envelope) {

        static Message of(JournalEntry entry) {
            return new Message(entry.kind().name(), entry.messageType(), entry.msgId(), entry.at(), entry.duplicate(),
                    entry.envelope());
        }
    }

    record SettlementView(String txId, String debtorAgentBic, String direction, long amountMinor, Instant settledAt) {

        static SettlementView of(Settlement settlement) {
            return new SettlementView(settlement.txId().value(), settlement.debtorAgentBic().value(),
                    settlement.direction().name(), settlement.amountMinor(), settlement.settledAt());
        }
    }

    private final CsmSimulation simulation;

    ControlController(CsmSimulation simulation) {
        this.simulation = simulation;
    }

    /** Replaces the counterparty accounts. */
    @PutMapping("/counterparty-accounts")
    ResponseEntity<Void> counterpartyAccounts(@RequestBody List<AccountBody> accounts) {
        simulation.setCounterpartyAccounts(accounts.stream()
                .map(a -> new CounterpartyAccount(new Iban(a.iban()), new PartyName(a.holderName())))
                .toList());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/scenarios/{creditorIban}")
    ResponseEntity<Void> scenario(@PathVariable String creditorIban, @RequestBody ScenarioBody body) {
        if (body.scenario() == null) {
            throw new InvalidControlRequestException("scenario", "scenario is required");
        }
        Scenario scenario;
        try {
            scenario = Scenario.parse(body.scenario());
        } catch (IllegalArgumentException e) {
            throw new InvalidControlRequestException("scenario", e.getMessage());
        }
        simulation.setScenario(new Iban(creditorIban), scenario);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/received")
    List<Message> received(@RequestParam String txId) {
        return simulation.journal(new TxId(txId)).stream().map(Message::of).toList();
    }

    @GetMapping("/settlements")
    List<SettlementView> settlements(@RequestParam(required = false) Instant since) {
        return simulation.settlements(since == null ? Instant.EPOCH : since).stream().map(SettlementView::of)
                .toList();
    }

    @PostMapping("/outage")
    ResponseEntity<Void> outage(@RequestBody OutageBody body) {
        if (body.mode() == null) {
            throw new InvalidControlRequestException("mode", "mode is required");
        }
        if (body.mode() == Outage.Mode.SLOW && (body.delayMs() == null || body.delayMs() < 0)) {
            throw new InvalidControlRequestException("delayMs", "SLOW needs delayMs, zero or more");
        }
        simulation.setOutage(new Outage(body.mode(),
                Duration.ofMillis(body.delayMs() == null ? 0 : body.delayMs())));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset")
    ResponseEntity<Void> reset() {
        simulation.reset();
        return ResponseEntity.noContent().build();
    }
}
