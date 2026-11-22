package com.tpe.riskservice.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.ReasonCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The invariants of the values a rule assessment reads and returns (specs/14-risk-service.md §3.1). */
class RuleValuesTest {

    @Test
    void aHitScoresAboveZeroUpToOneAndNoHitScoresZero() {
        assertThat(RuleResult.hit(1).score()).isEqualTo(1);
        assertThat(RuleResult.hit(0.5).score()).isEqualTo(0.5);
        assertThatIllegalArgumentException().isThrownBy(() -> RuleResult.hit(0));
        assertThatIllegalArgumentException().isThrownBy(() -> RuleResult.hit(1.01));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuleResult(false, 0.5));
    }

    @Test
    void onlyDenyAndReviewCarryAReasonCode() {
        assertThat(new Verdict(Decision.ALLOW, null, List.of()).reasonCode()).isNull();
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Verdict(Decision.ALLOW, ReasonCode.FR01, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Verdict(Decision.DENY, null, List.of()));
    }

    @Test
    void onlyAnOutgoingPaymentCarriesTheVopResult() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> payment(PaymentDirection.INCOMING, PayeeVerification.MATCH));
        assertThatIllegalArgumentException().isThrownBy(() -> payment(PaymentDirection.OUTGOING, null));
    }

    private static PaymentToAssess payment(PaymentDirection direction, PayeeVerification verification) {
        return new PaymentToAssess(direction, Money.parse("10.00"), new PartyName("Anna Schmidt"),
                new Iban("DE36100100100000000001"), new PartyName("Max Mustermann"),
                new Iban("DE07200200200123456789"), verification);
    }
}
