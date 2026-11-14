package com.tpe.paymentapi.api.security;

import com.tpe.paymentapi.api.Problems;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * The security filter chain (specs/10-payment-api.md §2, §3.1; specs/00-engineering-baseline.md §12.1): an OAuth2
 * resource server for Bearer JWTs of the configured issuer, one scope per endpoint, stateless. Without a token
 * {@code 401}, without the scope {@code 403}, both as Problem Details next to the {@code WWW-Authenticate} header
 * of RFC 6750. The probes, the metrics scrape and the OpenAPI document are open.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    public static final String PAYMENTS_WRITE = "SCOPE_payments:write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        AuthenticationEntryPoint unauthenticated = problem(new BearerTokenAuthenticationEntryPoint(), json);
        AccessDeniedHandler denied = problem(new BearerTokenAccessDeniedHandler(), json);
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/payments").hasAuthority(PAYMENTS_WRITE)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    private static AuthenticationEntryPoint problem(AuthenticationEntryPoint delegate, JsonMapper json) {
        return (request, response, e) -> {
            delegate.commence(request, response, e);
            write(response, json, "Authentication required: a valid Bearer token");
        };
    }

    private static AccessDeniedHandler problem(AccessDeniedHandler delegate, JsonMapper json) {
        return (request, response, e) -> {
            delegate.handle(request, response, e);
            write(response, json, "The token lacks the scope this request needs");
        };
    }

    /** After the delegate set the status and {@code WWW-Authenticate}. */
    private static void write(HttpServletResponse response, JsonMapper json, String detail) throws IOException {
        HttpStatus status = HttpStatus.valueOf(response.getStatus());
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", Problems.type(status.name()).toString());
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("code", status.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
