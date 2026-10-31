package com.tpe.pactsample.provider;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sample provider of the pact chain (issue #20): an account lookup over HTTP and an account opened event.
 * Replaced by the real providers once P-04 lands.
 */
@SpringBootApplication
public class SampleProviderApplication {

    public record Account(UUID id, String status) {}

    public record AccountOpened(UUID accountId, String status) {}

    record Problem(String code, String title) {}

    /** What the provider states seed. */
    @Component
    public static class Accounts {

        private final Set<UUID> open = ConcurrentHashMap.newKeySet();

        public void open(UUID id) {
            open.add(id);
        }

        public void clear() {
            open.clear();
        }

        boolean isOpen(UUID id) {
            return open.contains(id);
        }
    }

    @RestController
    static class AccountsController {

        private final Accounts accounts;

        AccountsController(Accounts accounts) {
            this.accounts = accounts;
        }

        @GetMapping("/sample/accounts/{id}")
        ResponseEntity<?> find(@PathVariable UUID id) {
            if (!accounts.isOpen(id)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(new Problem("ACCOUNT_NOT_FOUND", "Account not found"));
            }
            return ResponseEntity.ok(new Account(id, "OPEN"));
        }
    }

    /** The event's record headers, as the outbox relay would publish them (specs/01-shared-libraries.md §3.4). */
    static Map<String, Object> eventHeaders() {
        return Map.of("tpe-message-type", "AccountOpened", "tpe-schema-version", "1",
                "contentType", "application/json");
    }
}
