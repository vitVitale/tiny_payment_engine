package com.tpe.vopservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.vopservice.infrastructure.persistence.AuditPepper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code tpe.vop.audit-peppers} and {@code tpe.vop.active-pepper-version} are both required (specs/13-vop-service.md
 * §7, ADR-017): without them the service does not start.
 */
class AuditConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AuditConfiguration.class);

    @Test
    void theActivePepperHashesNewRows() {
        context.withPropertyValues(
                        "tpe.vop.audit-peppers.1=old-pepper",
                        "tpe.vop.audit-peppers.2=new-pepper",
                        "tpe.vop.active-pepper-version=2")
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    AuditPepper pepper = started.getBean(AuditPepper.class);
                    assertThat(pepper.version()).isEqualTo((short) 2);
                    assertThat(pepper.hash("Anna Schmidt"))
                            .isEqualTo(new AuditPepper((short) 2, "new-pepper").hash("Anna Schmidt"))
                            .isNotEqualTo(new AuditPepper((short) 1, "old-pepper").hash("Anna Schmidt"));
                });
    }

    @Test
    void startupFailsWithoutPeppers() {
        context.withPropertyValues("tpe.vop.active-pepper-version=1")
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("tpe.vop.audit-peppers"));
    }

    @Test
    void startupFailsWithoutTheActiveVersion() {
        context.withPropertyValues("tpe.vop.audit-peppers.1=pepper")
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("tpe.vop.active-pepper-version"));
    }

    @Test
    void startupFailsWhenTheActiveVersionHasNoPepper() {
        context.withPropertyValues("tpe.vop.audit-peppers.1=pepper", "tpe.vop.active-pepper-version=2")
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("tpe.vop.active-pepper-version"));
    }

    @Test
    void startupFailsWithABlankPepperOrAVersionOutOfRange() {
        context.withPropertyValues("tpe.vop.audit-peppers.1= ", "tpe.vop.active-pepper-version=1")
                .run(started -> assertThat(started).hasFailed());
        context.withPropertyValues("tpe.vop.audit-peppers.0=pepper", "tpe.vop.active-pepper-version=0")
                .run(started -> assertThat(started).hasFailed());
    }
}
