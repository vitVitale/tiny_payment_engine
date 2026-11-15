package com.tpe.csmsimulator.domain.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.csmsimulator.domain.scenarios.Scenario.Accept;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Delay;
import com.tpe.csmsimulator.domain.scenarios.Scenario.Reject;
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPT", "REJECT:AC04", "TIMEOUT", "DELAY:1500"})
    void aScenarioPrintsAsItIsWritten(String text) {
        assertThat(Scenario.parse(text).text()).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "accept", "REJECT", "REJECT:", "REJECT:TOOLONG", "DELAY", "DELAY:-1", "DELAY:soon",
            "NOPE"})
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
    @CsvSource({"10.91, REJECT:AC01", "10.92, REJECT:AC04", "10.93, TIMEOUT", "10.90, ACCEPT"})
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
