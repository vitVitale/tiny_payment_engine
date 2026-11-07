package com.tpe.ledgerservice.infrastructure.security;

import com.tpe.ledgerservice.api.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth2 resource server for staff JWTs (specs/00-engineering-baseline.md §12.1, ADR-012): the {@code roles} claim
 * becomes {@code ROLE_*} authorities, which the controllers check per endpoint with {@code @PreAuthorize}. Without a
 * token every API answers {@code 401 UNAUTHENTICATED}, without the role {@code 403 FORBIDDEN}, both as Problem Details
 * (00 §7) with the {@code WWW-Authenticate} header of RFC 6750. Probes and the OpenAPI document are open.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    static final String ROLES_CLAIM = "roles";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/v3/api-docs", "/v3/api-docs/**", "/error")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(staffRoles()))
                        .authenticationEntryPoint(unauthenticated(json))
                        .accessDeniedHandler(forbidden(json)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticated(json))
                        .accessDeniedHandler(forbidden(json)))
                .build();
    }

    static JwtAuthenticationConverter staffRoles() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** The bearer entry point sets status and {@code WWW-Authenticate}; the body is ours. */
    private static AuthenticationEntryPoint unauthenticated(JsonMapper json) {
        var bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, e) -> {
            bearer.commence(request, response, e);
            write(json, request, response,
                    Problems.problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required"));
        };
    }

    private static AccessDeniedHandler forbidden(JsonMapper json) {
        var bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, e) -> {
            bearer.handle(request, response, e);
            write(json, request, response,
                    Problems.problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "The token lacks the role this API needs"));
        };
    }

    private static void write(JsonMapper json, HttpServletRequest request, HttpServletResponse response,
            ProblemDetail problem) throws IOException {
        Map<String, Object> body = Problems.body(problem, request.getRequestURI());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), body);
    }
}
