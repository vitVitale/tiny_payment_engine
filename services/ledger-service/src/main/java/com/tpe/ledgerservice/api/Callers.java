package com.tpe.ledgerservice.api;

import com.tpe.ledgerservice.application.account.Caller;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The {@link Caller} of a request from its token (ADR-012): a staff JWT is its {@code sub}; risk-service's
 * client-credentials JWT, which carries scope {@code ledger:sanctions-block} and no roles, is the actor
 * {@code risk-service}.
 */
public final class Callers {

    public static final String BACKOFFICE_OPERATOR = "ROLE_BACKOFFICE_OPERATOR";
    public static final String COMPLIANCE_OFFICER = "ROLE_COMPLIANCE_OFFICER";
    public static final String SANCTIONS_BLOCK = "SCOPE_ledger:sanctions-block";

    private Callers() {}

    public static Caller of(Authentication authentication) {
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        boolean staff = authorities.stream().anyMatch(authority -> authority.startsWith("ROLE_"));
        // only risk-service holds the sanctions scope (ADR-012); any other client is audited by its own sub
        boolean riskService = !staff && authorities.contains(SANCTIONS_BLOCK);
        return new Caller(
                riskService ? Caller.RISK_SERVICE : authentication.getName(),
                authorities.contains(BACKOFFICE_OPERATOR),
                authorities.contains(COMPLIANCE_OFFICER) || authorities.contains(SANCTIONS_BLOCK));
    }
}
