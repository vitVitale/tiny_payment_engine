package com.tpe.contracts.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.ContractsModule;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Party;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The JSON of risk-service's assessment API (specs/14-risk-service.md §3.1) with {@link ContractsModule}. */
class RiskApiJsonTest {

    private static final PaymentId PAYMENT = new PaymentId(UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-c3d4e5f60718"));

    private final JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();

    @Test
    void anAssessmentRequestReadsTheShapeOfTheSpec() {
        AssessmentRequest request = json.readValue("""
                {"assessmentId":"0192f0e4-7b1c-7cc3-a1b2-c3d4e5f60718:OUTGOING",
                 "paymentId":"0192f0e4-7b1c-7cc3-a1b2-c3d4e5f60718","direction":"OUTGOING","txId":"TX-1",
                 "amountMinor":12345,"currency":"EUR",
                 "debtor":{"name":"Anna Schmidt","iban":"DE36100100100000000001"},
                 "creditor":{"name":"Max Mustermann","iban":"DE07200200200123456789"},
                 "debtorAgentBic":"TPEBDEFFXXX","creditorAgentBic":"PEERDEFFXXX",
                 "remittanceInformation":"Invoice 7","vopResult":"CLOSE_MATCH"}""", AssessmentRequest.class);

        assertThat(request).isEqualTo(new AssessmentRequest(PAYMENT.value() + ":OUTGOING", PAYMENT, Direction.OUTGOING,
                new TxId("TX-1"), Money.parse("123.45"),
                new Party(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001"), null),
                new Party(new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"), null),
                new Bic("TPEBDEFFXXX"), new Bic("PEERDEFFXXX"), new RemittanceInformation("Invoice 7"),
                VopResult.CLOSE_MATCH));
    }

    @Test
    void anIncomingRequestNeedsNoVopResult() {
        AssessmentRequest request = json.readValue("""
                {"assessmentId":"x","paymentId":"0192f0e4-7b1c-7cc3-a1b2-c3d4e5f60718","direction":"INCOMING",
                 "amountMinor":1,"currency":"EUR"}""", AssessmentRequest.class);

        assertThat(request.vopResult()).isNull();
        assertThat(request.direction()).isEqualTo(Direction.INCOMING);
    }

    @Test
    void anAmountOutsideTheValueObjectFailsTheRead() {
        assertThatThrownBy(() -> json.readValue("""
                {"amountMinor":1,"currency":"USD"}""", AssessmentRequest.class)).isNotNull();
    }

    @Test
    void theAssessmentIdIsThePaymentIdAndTheDirection() {
        assertThat(AssessmentRequest.assessmentId(PAYMENT, Direction.INCOMING))
                .isEqualTo("0192f0e4-7b1c-7cc3-a1b2-c3d4e5f60718:INCOMING");
    }

    @Test
    void aResponseLeavesOutTheReasonOfAnAllow() {
        Instant at = Instant.parse("2026-11-12T10:00:00Z");

        assertThat(json.writeValueAsString(new AssessmentResponse("a:OUTGOING", RiskDecision.ALLOW, null, List.of(),
                at))).isEqualTo("""
                {"assessmentId":"a:OUTGOING","decision":"ALLOW","ruleHits":[],\
                "assessedAt":"2026-11-12T10:00:00.000Z"}""");
        assertThat(json.writeValueAsString(new AssessmentResponse("a:OUTGOING", RiskDecision.DENY, ReasonCode.FR01,
                List.of(new RuleHit("AMOUNT_LIMIT", 1.0)), at))).isEqualTo("""
                {"assessmentId":"a:OUTGOING","decision":"DENY","reasonCode":"FR01",\
                "ruleHits":[{"ruleId":"AMOUNT_LIMIT","score":1.0}],"assessedAt":"2026-11-12T10:00:00.000Z"}""");
    }

    @Test
    void aResponseReadsBack() {
        var response = new AssessmentResponse("a:OUTGOING", RiskDecision.DENY, ReasonCode.RR04,
                List.of(new RuleHit("NON_EU_SANCTIONS", 0.98)), Instant.parse("2026-11-12T10:00:00Z"));

        assertThat(json.readValue(json.writeValueAsString(response), AssessmentResponse.class)).isEqualTo(response);
    }
}
