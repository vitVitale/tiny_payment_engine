package com.tpe.vopservice.infrastructure.config;

import com.tpe.vopservice.infrastructure.persistence.AuditPepper;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pepper of new {@code responder_audit} rows (specs/13-vop-service.md §5, §7, ADR-017). Both properties are
 * required: without them, or with an active version that has no pepper, the service does not start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditConfiguration.AuditPeppers.class)
class AuditConfiguration {

    @Bean
    AuditPepper auditPepper(AuditPeppers peppers) {
        return peppers.active();
    }

    /**
     * {@code tpe.vop.audit-peppers} (version → pepper, secret) and {@code tpe.vop.active-pepper-version}. Retired
     * versions stay listed until the last row hashed with them has expired; only the active one is used here.
     */
    @ConfigurationProperties("tpe.vop")
    record AuditPeppers(Map<Short, String> auditPeppers, Short activePepperVersion) {

        AuditPeppers {
            if (auditPeppers == null || auditPeppers.isEmpty()) {
                throw new IllegalStateException("tpe.vop.audit-peppers is required (ADR-017)");
            }
            if (activePepperVersion == null) {
                throw new IllegalStateException("tpe.vop.active-pepper-version is required (ADR-017)");
            }
            if (!auditPeppers.containsKey(activePepperVersion)) {
                throw new IllegalStateException("tpe.vop.active-pepper-version " + activePepperVersion
                        + " has no pepper in tpe.vop.audit-peppers");
            }
            requireUsable(auditPeppers);
            auditPeppers = Map.copyOf(auditPeppers);
        }

        /** Every listed pepper must serve evidence lookups, not only the active one: a version ≥ 1, not blank. */
        private static void requireUsable(Map<Short, String> peppers) {
            peppers.forEach((version, pepper) -> new AuditPepper(version, pepper));
        }

        AuditPepper active() {
            return new AuditPepper(activePepperVersion, auditPeppers.get(activePepperVersion));
        }

        @Override
        public String toString() {
            return "AuditPeppers[versions=" + auditPeppers.keySet() + ", active=" + activePepperVersion + "]";
        }
    }
}
