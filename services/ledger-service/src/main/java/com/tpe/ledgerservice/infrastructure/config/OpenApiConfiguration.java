package com.tpe.ledgerservice.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI document at {@code /v3/api-docs} (specs/00-engineering-baseline.md §7): staff JWT on every API. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String STAFF_JWT = "staffJwt";

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ledger-service").version("v1")
                        .description("Accounts, journal and memos (specs/12-ledger-service.md)"))
                .components(new Components().addSecuritySchemes(STAFF_JWT, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("staff: roles claim BACKOFFICE_OPERATOR, COMPLIANCE_OFFICER; risk-service: "
                                + "scope ledger:sanctions-block, ledger:customer-accounts")))
                .addSecurityItem(new SecurityRequirement().addList(STAFF_JWT));
    }
}
