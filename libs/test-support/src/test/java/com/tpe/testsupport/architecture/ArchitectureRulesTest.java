package com.tpe.testsupport.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tpe.fixturesvc.api.ApiUsingApplication;
import com.tpe.fixturesvc.api.ApiUsingInfrastructure;
import com.tpe.fixturesvc.api.ApiUsingKafkaTemplate;
import com.tpe.fixturesvc.api.ControllerWithTransactionalMethod;
import com.tpe.fixturesvc.api.ListenerUsingKafka;
import com.tpe.fixturesvc.api.UsesOpenTelemetryApi;
import com.tpe.fixturesvc.application.ReadsInstantNow;
import com.tpe.fixturesvc.application.ReadsZonedNow;
import com.tpe.fixturesvc.application.ObservationWithHighCardinality;
import com.tpe.fixturesvc.application.ServiceUsingJdbc;
import com.tpe.fixturesvc.application.TagsSpanWithNumber;
import com.tpe.fixturesvc.application.TagsSpanWithString;
import com.tpe.fixturesvc.application.TransactionalPaymentService;
import com.tpe.fixturesvc.domain.AccountBalanceSnapshot;
import com.tpe.fixturesvc.domain.CompliantMoney;
import com.tpe.fixturesvc.domain.CompliantPayment;
import com.tpe.fixturesvc.domain.DomainUsingApi;
import com.tpe.fixturesvc.domain.DomainUsingApplication;
import com.tpe.fixturesvc.domain.DomainUsingInfrastructure;
import com.tpe.fixturesvc.domain.DomainUsingJackson;
import com.tpe.fixturesvc.domain.DomainUsingSpring;
import com.tpe.fixturesvc.domain.MoneyView;
import com.tpe.fixturesvc.domain.PaymentAmount;
import com.tpe.fixturesvc.domain.ReadsLegacyTime;
import com.tpe.fixturesvc.domain.ReadsSystemTime;
import com.tpe.fixturesvc.domain.RiskScore;
import com.tpe.fixturesvc.infrastructure.JakartaTransactionalRepository;
import com.tpe.fixturesvc.infrastructure.KafkaAndJdbcAdapter;
import com.tpe.fixturesvc.infrastructure.OpenTelemetryAdapter;
import com.tpe.fixturesvc.infrastructure.ReadsLocalDateTimeNow;
import com.tpe.fixturesvc.infrastructure.TransactionalRepository;
import com.tpe.observability.fixture.TagsInObservability;
import org.junit.jupiter.api.Test;

/** Each rule passes on a compliant class and fails on a class that violates it on purpose. */
class ArchitectureRulesTest {

    @Test
    void domainMustNotDependOnInfrastructure() {
        assertViolates(ArchitectureRules.DOMAIN_IS_ISOLATED, DomainUsingInfrastructure.class);
        assertHolds(ArchitectureRules.DOMAIN_IS_ISOLATED, CompliantPayment.class);
    }

    @Test
    void domainMustNotDependOnApiOrApplication() {
        assertViolates(ArchitectureRules.DOMAIN_IS_ISOLATED, DomainUsingApi.class);
        assertViolates(ArchitectureRules.DOMAIN_IS_ISOLATED, DomainUsingApplication.class);
    }

    @Test
    void domainMustNotDependOnSpringOrJackson() {
        assertViolates(ArchitectureRules.DOMAIN_IS_ISOLATED, DomainUsingSpring.class);
        assertViolates(ArchitectureRules.DOMAIN_IS_ISOLATED, DomainUsingJackson.class);
    }

    @Test
    void apiMustNotDependOnInfrastructure() {
        assertViolates(ArchitectureRules.API_DOES_NOT_USE_INFRASTRUCTURE, ApiUsingInfrastructure.class);
        assertHolds(ArchitectureRules.API_DOES_NOT_USE_INFRASTRUCTURE, ApiUsingApplication.class);
    }

    @Test
    void transactionalOnlyOnApplicationClasses() {
        assertViolates(ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION, TransactionalRepository.class);
        assertViolates(ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION, JakartaTransactionalRepository.class);
        assertViolates(ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION, ControllerWithTransactionalMethod.class);
        assertHolds(ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION, TransactionalPaymentService.class);
    }

    @Test
    void kafkaAndJdbcOnlyInInfrastructure() {
        assertViolates(ArchitectureRules.KAFKA_AND_JDBC_ONLY_IN_INFRASTRUCTURE, ServiceUsingJdbc.class);
        assertViolates(ArchitectureRules.KAFKA_AND_JDBC_ONLY_IN_INFRASTRUCTURE, ApiUsingKafkaTemplate.class);
        assertHolds(ArchitectureRules.KAFKA_AND_JDBC_ONLY_IN_INFRASTRUCTURE, KafkaAndJdbcAdapter.class);
    }

    @Test
    void kafkaListenersMayLiveInApi() {
        // 00 §3 puts Kafka listeners in api/, so the listener annotations are allowed there
        assertHolds(ArchitectureRules.KAFKA_AND_JDBC_ONLY_IN_INFRASTRUCTURE, ListenerUsingKafka.class);
    }

    @Test
    void timeIsReadOnlyThroughAClock() {
        assertViolates(ArchitectureRules.NO_CLOCKLESS_TIME_READS, ReadsInstantNow.class);
        assertViolates(ArchitectureRules.NO_CLOCKLESS_TIME_READS, ReadsLocalDateTimeNow.class);
        assertViolates(ArchitectureRules.NO_CLOCKLESS_TIME_READS, ReadsSystemTime.class);
        assertViolates(ArchitectureRules.NO_CLOCKLESS_TIME_READS, ReadsZonedNow.class);
        assertViolates(ArchitectureRules.NO_CLOCKLESS_TIME_READS, ReadsLegacyTime.class);
        assertHolds(ArchitectureRules.NO_CLOCKLESS_TIME_READS, CompliantPayment.class);
    }

    @Test
    void moneyIsNeverFloatingPoint() {
        assertViolates(ArchitectureRules.NO_FLOATING_POINT_MONEY, PaymentAmount.class);
        assertViolates(ArchitectureRules.NO_FLOATING_POINT_MONEY, MoneyView.class);
        assertViolates(ArchitectureRules.NO_FLOATING_POINT_MONEY, AccountBalanceSnapshot.class);
        assertHolds(ArchitectureRules.NO_FLOATING_POINT_MONEY, CompliantMoney.class);
        assertHolds(ArchitectureRules.NO_FLOATING_POINT_MONEY, RiskScore.class);
    }

    @Test
    void telemetryAttributesOnlyThroughObservability() {
        ArchRule rule = ArchitectureRules.TELEMETRY_ATTRIBUTES_ONLY_THROUGH_OBSERVABILITY;
        assertViolates(rule, TagsSpanWithString.class);
        assertViolates(rule, ObservationWithHighCardinality.class);
        assertViolates(rule, UsesOpenTelemetryApi.class);
        assertHolds(rule, TagsSpanWithNumber.class);
        assertHolds(rule, OpenTelemetryAdapter.class);
        assertHolds(rule, TagsInObservability.class);
    }

    private static void assertViolates(ArchRule rule, Class<?> violator) {
        assertThat(rule.evaluate(new ClassFileImporter().importClasses(violator)).hasViolation())
                .as("%s should fail on %s", rule.getDescription(), violator.getSimpleName())
                .isTrue();
    }

    private static void assertHolds(ArchRule rule, Class<?> compliant) {
        assertThat(rule.evaluate(new ClassFileImporter().importClasses(compliant)).hasViolation())
                .as("%s should hold on %s", rule.getDescription(), compliant.getSimpleName())
                .isFalse();
    }
}
