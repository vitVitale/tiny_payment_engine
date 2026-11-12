package com.tpe.vopservice.infrastructure.config;

import com.tpe.contracts.ContractsModule;
import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.UlidGenerator;
import com.tpe.vopservice.application.metrics.VopRequestMetrics;
import com.tpe.vopservice.application.references.VopResults;
import com.tpe.vopservice.application.requesting.CounterpartyVop;
import com.tpe.vopservice.application.requesting.RequestingService;
import com.tpe.vopservice.application.responding.RespondingService;
import com.tpe.vopservice.domain.matching.HolderVerifier;
import com.tpe.vopservice.domain.matching.NameMatcher;
import com.tpe.vopservice.domain.matching.NameNormaliser;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time, JSON of the internal API, the name normaliser and matcher, and the requesting service
 * (specs/00-engineering-baseline.md §6, specs/13-vop-service.md §3.1, §6.1, §6.2, §7).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({VopConfiguration.Normalisation.class, VopConfiguration.Matching.class})
class VopConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The value objects of the {@code contracts} DTOs as strings (01 §4); Spring Boot registers it with Jackson. */
    @Bean
    ContractsModule contractsModule() {
        return new ContractsModule();
    }

    /** {@code vopReference} = {@code VOP-} + a ULID; its random part from a secure source, as a reference is a key. */
    @Bean
    UlidGenerator ulidGenerator(Clock clock) {
        return new UlidGenerator(clock, new SecureRandom());
    }

    @Bean
    RequestingService requestingService(BankDirectory directory, RespondingService responding,
            CounterpartyVop counterparty, VopResults results, UlidGenerator ulids, VopRequestMetrics metrics,
            Clock clock, @Value("${tpe.vop.reference-validity}") Duration referenceValidity) {
        return new RequestingService(directory, responding, counterparty, results, ulids, metrics, clock,
                referenceValidity);
    }

    @Bean
    NameNormaliser nameNormaliser(Normalisation normalisation) {
        return new NameNormaliser(normalisation.legalForms(), normalisation.titles());
    }

    @Bean
    NameMatcher nameMatcher(Matching matching) {
        return new NameMatcher(matching.matchThreshold(), matching.closeMatchThreshold());
    }

    @Bean
    HolderVerifier holderVerifier(NameNormaliser normaliser, NameMatcher matcher) {
        return new HolderVerifier(normaliser, matcher);
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

    /**
     * {@code tpe.vop.match-threshold} and {@code tpe.vop.close-match-threshold} (13 §7), default 0.95 and 0.85;
     * {@link NameMatcher} refuses thresholds out of order.
     */
    @ConfigurationProperties("tpe.vop")
    record Matching(Double matchThreshold, Double closeMatchThreshold) {

        Matching {
            matchThreshold = matchThreshold == null ? NameMatcher.DEFAULT_MATCH_THRESHOLD : matchThreshold;
            closeMatchThreshold = closeMatchThreshold == null
                    ? NameMatcher.DEFAULT_CLOSE_MATCH_THRESHOLD
                    : closeMatchThreshold;
        }
    }
}
