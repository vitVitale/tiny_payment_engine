package com.tpe.ledgerservice.infrastructure.config;

import com.tpe.ledgerservice.domain.account.IbanGenerator;
import com.tpe.paymentdomain.UuidV7Generator;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Time, ids and IBANs (specs/00-engineering-baseline.md §4, §6; specs/12-ledger-service.md §6.4, §7). */
@Configuration(proxyBeanMethods = false)
class LedgerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Account ids: UUID v7. */
    @Bean
    UuidV7Generator uuidV7Generator(Clock clock) {
        return new UuidV7Generator(clock, new SecureRandom());
    }

    /** {@code tpe.bank.bank-code} is also the Flyway placeholder {@code bankCode} of the technical accounts' IBANs. */
    @Bean
    IbanGenerator ibanGenerator(@Value("${tpe.bank.bank-code}") String bankCode) {
        return new IbanGenerator(bankCode);
    }
}
