package com.tpe.vopservice.infrastructure.config;

import com.tpe.vopservice.domain.matching.HolderVerifier;
import com.tpe.vopservice.domain.matching.NameMatcher;
import com.tpe.vopservice.domain.matching.NameNormaliser;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time, the name normaliser and the name matcher (specs/00-engineering-baseline.md §6, specs/13-vop-service.md §6.2,
 * §7).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({VopConfiguration.Normalisation.class, VopConfiguration.Matching.class})
class VopConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
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
