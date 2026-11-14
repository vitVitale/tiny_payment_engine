package com.tpe.paymentapi.infrastructure.config;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code payment-api.yaml}, the contract for channel teams (specs/10-payment-api.md §3): Bearer JWT, a fixed server
 * so the document does not depend on the host that serves it, Problem Details with the members the API adds, and an
 * IBAN or BIC as the string it is on the wire, not an object with a {@code value} (specs/00-engineering-baseline.md §7).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static {
        SpringDocUtils.getConfig()
                .replaceWithClass(Iban.class, String.class)
                .replaceWithClass(Bic.class, String.class);
    }

    @Bean
    OpenAPI paymentApiDocument() {
        return new OpenAPI()
                .info(new Info().title("payment-api").version("v1")
                        .description("Customer channels' API for SCT Inst payments (UC-01, UC-02, UC-07)"))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("Claims client_id, scope and accounts (specs/00-engineering-baseline.md §12.1)")));
    }

    /** {@code code} and {@code errors} are members of the body, not the {@code properties} map Spring keeps them in. */
    @Bean
    OpenApiCustomizer problemMembers() {
        return openApi -> {
            Schema<?> problem = openApi.getComponents().getSchemas().get("ProblemDetail");
            if (problem == null) {
                return;
            }
            problem.getProperties().remove("properties");
            problem.addProperty("code", new StringSchema()
                    .description("Machine-readable error code (specs/10-payment-api.md §3.5)"));
            problem.addProperty("errors", new ArraySchema()
                    .description("VALIDATION_FAILED only: the invalid fields")
                    .items(new ObjectSchema()
                            .addProperty("field", new StringSchema().description("JSON path, e.g. debtor.iban"))
                            .addProperty("code", new StringSchema().description("Field error code, e.g. INVALID_IBAN"))));
        };
    }
}
