package com.tpe.vopservice.infrastructure.config;

import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.InMemoryBankDirectory;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The bank directory (specs/01-shared-libraries.md §1.4), bound from {@code tpe.bank-directory} of the YAML file at
 * {@code tpe.bank-directory.location} (application.yml imports it; default the build's copy of
 * deploy/config/bank-directory.yml). The responder accepts only participants listed here (specs/13-vop-service.md
 * §3.2).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BankDirectoryConfiguration.BankDirectoryProperties.class)
class BankDirectoryConfiguration {

    @Bean
    BankDirectory bankDirectory(BankDirectoryProperties properties) {
        if (properties.ownBankCode() == null || properties.ownBic() == null || properties.banks() == null) {
            throw new IllegalStateException(
                    "tpe.bank-directory needs ownBankCode, ownBic and banks (tpe.bank-directory.location)");
        }
        return new InMemoryBankDirectory(properties.ownBankCode(), new Bic(properties.ownBic()),
                properties.banks().stream()
                        .map(bank -> new BankEntry(bank.bankCode(), new Bic(bank.bic()), bank.sctInstReachable(),
                                Optional.ofNullable(bank.vopEndpoint())))
                        .toList());
    }

    @ConfigurationProperties("tpe.bank-directory")
    record BankDirectoryProperties(String ownBankCode, String ownBic, List<Bank> banks) {

        record Bank(String bankCode, String bic, boolean sctInstReachable, URI vopEndpoint) {}
    }
}
