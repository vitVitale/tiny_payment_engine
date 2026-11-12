package com.tpe.vopservice.infrastructure.config;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document shows the {@code contracts} value objects as {@code ContractsModule} writes them: an IBAN or a
 * BIC is a string, not an object with a {@code value} (specs/00-engineering-baseline.md §7, 01 §4).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static {
        SpringDocUtils.getConfig()
                .replaceWithClass(Iban.class, String.class)
                .replaceWithClass(Bic.class, String.class);
    }
}
