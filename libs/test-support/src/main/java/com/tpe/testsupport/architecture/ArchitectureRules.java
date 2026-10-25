package com.tpe.testsupport.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameContaining;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.Date;
import java.util.List;

/**
 * Layering, money and time rules shared by every service and the simulator (specs/00-engineering-baseline.md §3,
 * §5, §6). Run them from a service with {@code @ArchTest static final ArchTests RULES = ArchTests.in(ArchitectureRules.class);}.
 *
 * <p>Packages follow the service layout {@code com.tpe.<svc>.{api,application,domain,infrastructure}}. Rules allow
 * an empty selection, so a service that has no {@code domain} package yet still passes.
 */
public final class ArchitectureRules {

    private static final String API = "com.tpe.*.api..";
    private static final String APPLICATION = "com.tpe.*.application..";
    private static final String DOMAIN = "com.tpe.*.domain..";
    private static final String INFRASTRUCTURE = "com.tpe.*.infrastructure..";

    // by name, so this module needs no Spring on its classpath
    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    private static final String SPRING_KAFKA = "org.springframework.kafka..";
    private static final String SPRING_JDBC = "org.springframework.jdbc..";
    // what a Kafka listener in api/ needs: @KafkaListener, Acknowledgment, KafkaHeaders
    private static final String[] SPRING_KAFKA_LISTENER_API = {
            "org.springframework.kafka.annotation..", "org.springframework.kafka.support.."};

    @ArchTest
    public static final ArchRule DOMAIN_IS_ISOLATED = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    API, APPLICATION, INFRASTRUCTURE,
                    "org.springframework..",
                    // Jackson 2 annotations and Jackson 3 (Spring Boot 4)
                    "com.fasterxml.jackson..", "tools.jackson..")
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule API_DOES_NOT_USE_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAPackage(INFRASTRUCTURE)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule TRANSACTIONAL_ONLY_IN_APPLICATION = CompositeArchRule
            .of(noClasses()
                    .that().resideOutsideOfPackage(APPLICATION)
                    .should().beAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould().beAnnotatedWith(JAKARTA_TRANSACTIONAL)
                    .allowEmptyShould(true))
            .and(noMethods()
                    .that().areDeclaredInClassesThat().resideOutsideOfPackage(APPLICATION)
                    .should().beAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould().beAnnotatedWith(JAKARTA_TRANSACTIONAL)
                    .allowEmptyShould(true));

    /** Kafka listeners belong in {@code api} (§3), so {@code api} may use the listener annotations and support types. */
    @ArchTest
    public static final ArchRule KAFKA_AND_JDBC_ONLY_IN_INFRASTRUCTURE = CompositeArchRule
            .of(noClasses()
                    .that().resideOutsideOfPackages(INFRASTRUCTURE, API)
                    .should().dependOnClassesThat().resideInAnyPackage(SPRING_KAFKA, SPRING_JDBC)
                    .allowEmptyShould(true))
            .and(noClasses()
                    .that().resideInAPackage(API)
                    .should().dependOnClassesThat(resideInAnyPackage(SPRING_KAFKA, SPRING_JDBC)
                            .and(not(resideInAnyPackage(SPRING_KAFKA_LISTENER_API))))
                    .allowEmptyShould(true));

    /**
     * {@code Instant.now()}, {@code ZonedDateTime.now(zone)}, ..., {@code System.currentTimeMillis()},
     * {@code new Date()} and {@code Calendar.getInstance(..)}; only {@code now(Clock)} is fine.
     */
    @ArchTest
    public static final ArchRule NO_CLOCKLESS_TIME_READS = noClasses()
            .should().callMethodWhere(readsTimeWithoutAClock())
            .orShould().callConstructor(Date.class)
            .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule NO_FLOATING_POINT_MONEY = noFields()
            .that().areDeclaredInClassesThat(simpleNameContaining("Amount")
                    .or(simpleNameContaining("Money"))
                    .or(simpleNameContaining("Balance")))
            .should().haveRawType(double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(Float.class)
            .allowEmptyShould(true);

    private ArchitectureRules() {}

    private static DescribedPredicate<JavaMethodCall> readsTimeWithoutAClock() {
        return DescribedPredicate.describe("a clock-less time read", call -> {
            MethodCallTarget target = call.getTarget();
            String owner = target.getOwner().getName();
            String name = target.getName();
            boolean javaTimeNow = target.getOwner().getPackageName().equals("java.time") && name.equals("now")
                    && !target.getRawParameterTypes().stream().map(JavaClass::getName).toList().equals(List.of("java.time.Clock"));
            boolean systemMillis = owner.equals("java.lang.System") && name.equals("currentTimeMillis");
            boolean calendarNow = owner.equals("java.util.Calendar") && name.equals("getInstance");
            return javaTimeNow || systemMillis || calendarNow;
        });
    }
}
