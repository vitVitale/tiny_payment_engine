package com.tpe.riskservice.infrastructure.config;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.math.BigDecimal;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document shows the {@code contracts} value objects as {@code ContractsModule} writes them: strings,
 * not objects with a {@code value}, and an unwrapped {@code Money} as {@code amountMinor} and {@code currency}
 * (specs/00-engineering-baseline.md §7, 01 §4).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static {
        SpringDocUtils.getConfig()
                .replaceWithClass(Iban.class, String.class)
                .replaceWithClass(Bic.class, String.class)
                .replaceWithClass(PartyName.class, String.class)
                .replaceWithClass(PaymentId.class, String.class)
                .replaceWithClass(TxId.class, String.class)
                .replaceWithClass(RemittanceInformation.class, String.class)
                .replaceWithClass(StatusReason.class, String.class);
    }

    /** {@code AssessmentRequest.amount} is {@code @JsonUnwrapped}; springdoc does not see the mixin's members. */
    @Bean
    OpenApiCustomizer unwrappedMoney() {
        return openApi -> {
            Schema<?> request = openApi.getComponents().getSchemas().get("AssessmentRequest");
            if (request != null) {
                request.addProperty("amountMinor", new IntegerSchema().format("int64").minimum(BigDecimal.ONE)
                        .description("Amount in euro cents"));
                request.addProperty("currency", new StringSchema()._enum(List.of("EUR")));
            }
        };
    }
}
