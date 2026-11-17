package com.tpe.csmsimulator.domain.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.csmsimulator.domain.scenarios.Scenario.Accept;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Delay;
import com.tpe.csmsimulator.domain.scenarios.Scenario.InvestigationSilent;
import com.tpe.csmsimulator.domain.scenarios.Scenario.LateStatus;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Lost;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Reject;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Silent;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Timeout;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ScenariosTest {

    private static final Iban CREDITOR = new Iban("DE07200200200123456789");
    private static final Iban OTHER_CREDITOR = new Iban("DE36100100100000000001");

    private final Scenarios scenarios = new Scenarios();

    @Test
    void parsesTheScenariosOfTheOutgoingScheme() {
        assertThat(Scenario.parse("ACCEPT")).isEqualTo(new Accept());
        assertThat(Scenario.parse("REJECT:AC04")).isEqualTo(new Reject(ReasonCode.AC04));
        assertThat(Scenario.parse("REJECT:XY99")).isEqualTo(new Reject(ReasonCode.parse("XY99")));
        assertThat(Scenario.parse("TIMEOUT")).isEqualTo(new Timeout());
        assertThat(Scenario.parse("DELAY:1500")).isEqualTo(new Delay(Duration.ofMillis(1500)));
        assertThat(Scenario.parse("SILENT")).isEqualTo(new Silent());
        assertThat(Scenario.parse("LOST")).isEqualTo(new Lost());
        assertThat(Scenario.parse("LATE_STATUS:21000")).isEqualTo(new LateStatus(Duration.ofSeconds(21)));
    }

    @Test
    void investigationSilentIsAcceptOrJoinsAPaymentScenario() {
        assertThat(Scenario.parse("INVESTIGATION_SILENT")).isEqualTo(new InvestigationSilent(new Accept()));
        assertThat(Scenario.parse("SILENT+INVESTIGATION_SILENT")).isEqualTo(new InvestigationSilent(new Silent()));
        assertThat(Scenario.parse("INVESTIGATION_SILENT+SILENT")).isEqualTo(new InvestigationSilent(new Silent()));
    }

    @Test
    void onlyInvestigationSilentIgnoresInvestigations() {
        assertThat(Scenario.parse("SILENT+INVESTIGATION_SILENT").ignoresInvestigations()).isTrue();
        assertThat(Scenario.parse("SILENT+INVESTIGATION_SILENT").payment()).isEqualTo(new Silent());
        assertThat(Scenario.parse("SILENT").ignoresInvestigations()).isFalse();
        assertThat(Scenario.parse("SILENT").payment()).isEqualTo(new Silent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPT", "REJECT:AC04", "TIMEOUT", "DELAY:1500", "SILENT", "LOST", "LATE_STATUS:21000",
            "INVESTIGATION_SILENT", "SILENT+INVESTIGATION_SILENT"})
    void aScenarioPrintsAsItIsWritten(String text) {
        assertThat(Scenario.parse(text).text()).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "accept", "REJECT", "REJECT:", "REJECT:TOOLONG", "DELAY", "DELAY:-1", "DELAY:soon",
            "NOPE", "SILENT:1", "LATE_STATUS", "LATE_STATUS:-1", "SILENT+LOST", "INVESTIGATION_SILENT+INVESTIGATION_SILENT",
            "SILENT+", "+SILENT", "VOP_SLOW"})
    void refusesWhatIsNotAScenario(String text) {
        assertThatThrownBy(() -> Scenario.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPaymentWithoutAScenarioIsAccepted() {
        assertThat(scenarios.scenarioFor(CREDITOR, Money.parse("10.00"))).isEqualTo(new Accept());
    }

    @Test
    void theScenarioOfTheCreditorAppliesToPaymentsToThatIbanOnly() {
        scenarios.set(CREDITOR, new Reject(ReasonCode.AC04));

        assertThat(scenarios.scenarioFor(CREDITOR, Money.parse("10.00"))).isEqualTo(new Reject(ReasonCode.AC04));
        assertThat(scenarios.scenarioFor(OTHER_CREDITOR, Money.parse("10.00"))).isEqualTo(new Accept());
    }

    @ParameterizedTest
    @CsvSource({"10.91, REJECT:AC01", "10.92, REJECT:AC04", "10.93, TIMEOUT", "10.94, SILENT", "10.90, ACCEPT"})
    void theCentsOfAnAmountWithoutAScenarioPickOne(String amount, String scenario) {
        assertThat(scenarios.scenarioFor(CREDITOR, Money.parse(amount))).isEqualTo(Scenario.parse(scenario));
    }

    @Test
    void anExplicitScenarioWinsOverTheCents() {
        scenarios.set(CREDITOR, new Accept());

        assertThat(scenarios.scenarioFor(CREDITOR, Money.parse("10.91"))).isEqualTo(new Accept());
    }

    @Test
    void clearForgetsEveryScenario() {
        scenarios.set(CREDITOR, new Timeout());

        scenarios.clear();

        assertThat(scenarios.scenarioFor(CREDITOR, Money.parse("10.00"))).isEqualTo(new Accept());
    }
}
