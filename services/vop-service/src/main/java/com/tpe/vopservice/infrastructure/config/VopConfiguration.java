package com.tpe.vopservice.infrastructure.config;

import com.tpe.vopservice.domain.matching.NameNormaliser;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Time and the name normaliser (specs/00-engineering-baseline.md §6, specs/13-vop-service.md §6.2, §7). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VopConfiguration.Normalisation.class)
class VopConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NameNormaliser nameNormaliser(Normalisation normalisation) {
        return new NameNormaliser(normalisation.legalForms(), normalisation.titles());
    }

    /**
     * {@code tpe.vop.legal-forms} and {@code tpe.vop.titles} (13 §7): the tokens normalisation drops, as written; a
     * property left unset keeps the list of 13 §6.2.
     */
    @ConfigurationProperties("tpe.vop")
    record Normalisation(List<String> legalForms, List<String> titles) {

        Normalisation {
            legalForms = legalForms == null ? NameNormaliser.DEFAULT_LEGAL_FORMS : List.copyOf(legalForms);
            titles = titles == null ? NameNormaliser.DEFAULT_TITLES : List.copyOf(titles);
        }
    }
}
