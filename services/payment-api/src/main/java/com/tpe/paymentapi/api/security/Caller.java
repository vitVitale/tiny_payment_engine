package com.tpe.paymentapi.api.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The API client and the accounts its subject may debit, from the token's {@code client_id} and {@code accounts}
 * claims (specs/00-engineering-baseline.md §12.1).
 */
public record Caller(String clientId, Set<String> accounts) {

    static final String CLIENT_ID = "client_id";
    static final String ACCOUNTS = "accounts";

    /** @throws InvalidTokenClaimsException without a {@code client_id} or an {@code accounts} claim (it may be empty) */
    public static Caller of(Jwt token) {
        String clientId = token.getClaimAsString(CLIENT_ID);
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidTokenClaimsException("the token has no client_id");
        }
        if (!token.hasClaim(ACCOUNTS)) {
            throw new InvalidTokenClaimsException("the token has no accounts claim");
        }
        List<String> accounts = token.getClaimAsStringList(ACCOUNTS);
        return new Caller(clientId, accounts.stream().map(Caller::normalised)
                .collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * {@code 403 DEBTOR_ACCOUNT_NOT_OWNED} unless {@code iban} is one of the token's accounts (UC-01 1a). A missing
     * IBAN is left to validation ({@code REQUIRED}); an IBAN is compared as {@code Iban} writes it.
     */
    public void requireOwns(String iban) {
        if (iban != null && !iban.isBlank() && !accounts.contains(normalised(iban))) {
            throw new DebtorAccountNotOwnedException();
        }
    }

    private static String normalised(String iban) {
        return iban.replace(" ", "").toUpperCase(Locale.ROOT);
    }

    /** The accounts are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "Caller[clientId=" + clientId + ", accounts=" + accounts.size() + "]";
    }
}
