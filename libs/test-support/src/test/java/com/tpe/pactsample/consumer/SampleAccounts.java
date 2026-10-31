package com.tpe.pactsample.consumer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * The sample consumer's client adapter (issue #20): what a consumer pact drives (20 §10.2). An unknown account is a
 * {@code 404} with code {@code ACCOUNT_NOT_FOUND}, mapped to empty. Replaced by the real adapters once P-04 lands.
 */
public final class SampleAccounts {

    public record Account(UUID id, String status) {}

    record Problem(String code) {}

    private final RestClient client;

    public SampleAccounts(String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    public Optional<Account> find(UUID id) {
        return client.get().uri("/sample/accounts/{id}", id).exchange((request, response) -> {
            if (response.getStatusCode() == HttpStatus.NOT_FOUND
                    && "ACCOUNT_NOT_FOUND".equals(response.bodyTo(Problem.class).code())) {
                return Optional.empty();
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("sample provider answered " + response.getStatusCode());
            }
            return Optional.of(response.bodyTo(Account.class));
        });
    }
}
