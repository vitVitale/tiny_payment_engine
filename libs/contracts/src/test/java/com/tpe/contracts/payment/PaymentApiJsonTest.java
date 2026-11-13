package com.tpe.contracts.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.ContractsModule;
import com.tpe.contracts.Direction;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The JSON of payment-orchestrator's payment command API (specs/11-payment-orchestrator.md §3.1). */
class PaymentApiJsonTest {

    private static final PaymentId PAYMENT = new PaymentId(UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-c3d4e5f60718"));
    private static final UUID COMMAND = UUID.fromString("5b0a7c0e-1f2d-5e3c-9a4b-6c7d8e9f0a1b");

    private final JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();

    @Test
    void aSubmitReadsTheShapeOfTheSpec() {
        SubmitPayment submit = json.readValue("""
                {"commandId":"5b0a7c0e-1f2d-5e3c-9a4b-6c7d8e9f0a1b","clientId":"mobile-app",
                 "endToEndId":"INV-2026-0042",
                 "debtor":{"name":"Anna Schmidt","iban":"DE36100100100000000001"},
                 "creditor":{"name":"Max Mustermann","iban":"DE07200200200123456789","bic":"PEERDEFFXXX"},
                 "amountMinor":12345,"currency":"EUR","remittanceInformation":"Invoice 42",
                 "vopReference":"VOP-01JAB","vopResult":"CLOSE_MATCH"}""", SubmitPayment.class);

        assertThat(submit).isEqualTo(new SubmitPayment(COMMAND, "mobile-app", new EndToEndId("INV-2026-0042"),
                new SubmitPayment.Debtor(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001")),
                new SubmitPayment.Creditor(new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"),
                        new Bic("PEERDEFFXXX")),
                Money.parse("123.45"), new RemittanceInformation("Invoice 42"), "VOP-01JAB", VopResult.CLOSE_MATCH));
    }

    @Test
    void aSubmitWithoutBicOrRemittanceLeavesThemOut() {
        var submit = new SubmitPayment(COMMAND, "mobile-app", EndToEndId.NOT_PROVIDED,
                new SubmitPayment.Debtor(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001")),
                new SubmitPayment.Creditor(new PartyName("Max Mustermann"), new Iban("DE07200200200123456789"), null),
                Money.parse("1.00"), null, "VOP-1", VopResult.MATCH);

        String written = json.writeValueAsString(submit);

        assertThat(written).doesNotContain("bic").doesNotContain("remittanceInformation");
        assertThat(json.readValue(written, SubmitPayment.class)).isEqualTo(submit);
    }

    @Test
    void theAcceptedPaymentIsItsIdStateTxIdAndAcceptance() {
        assertThat(json.writeValueAsString(new PaymentAccepted(PAYMENT, PaymentState.RECEIVED,
                new TxId("TPE01JABCDEFGHJKMNPQRSTVWXYZ"), Instant.parse("2026-11-13T10:00:00.25Z")))).isEqualTo("""
                {"paymentId":"0192f0e4-7b1c-7cc3-a1b2-c3d4e5f60718","state":"RECEIVED",\
                "txId":"TPE01JABCDEFGHJKMNPQRSTVWXYZ","acceptedAt":"2026-11-13T10:00:00.250Z"}""");
    }

    @Test
    void theViewWritesTheFieldsOfTheSpecWithNullsForWhatIsNotThereYet() {
        var view = new PaymentView(PAYMENT, Direction.OUTGOING, false, PaymentState.RECEIVED, null,
                new TxId("TPE01JABCDEFGHJKMNPQRSTVWXYZ"), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"),
                new EndToEndId("INV-2026-0042"), Money.parse("123.45"),
                new PaymentView.Holder(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001")),
                new PaymentView.Holder(new PartyName("Max Mustermann"), new Iban("DE07200200200123456789")), null,
                Instant.parse("2026-11-13T10:00:00.25Z"), null);

        assertThat(json.writeValueAsString(view)).isEqualTo("""
                {"paymentId":"0192f0e4-7b1c-7cc3-a1b2-c3d4e5f60718","direction":"OUTGOING","onUs":false,\
                "state":"RECEIVED","reasonCode":null,"txId":"TPE01JABCDEFGHJKMNPQRSTVWXYZ",\
                "uetr":"6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b","endToEndId":"INV-2026-0042",\
                "amountMinor":12345,"currency":"EUR",\
                "debtor":{"name":"Anna Schmidt","iban":"DE36100100100000000001"},\
                "creditor":{"name":"Max Mustermann","iban":"DE07200200200123456789"},\
                "remittanceInformation":null,"acceptedAt":"2026-11-13T10:00:00.250Z","finalisedAt":null}""");
    }

    @Test
    void aRejectedViewReadsBack() {
        var view = new PaymentView(PAYMENT, Direction.OUTGOING, true, PaymentState.REJECTED, ReasonCode.AM04,
                new TxId("TPE01JABCDEFGHJKMNPQRSTVWXYZ"), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"),
                EndToEndId.NOT_PROVIDED, Money.parse("1.00"),
                new PaymentView.Holder(new PartyName("Anna Schmidt"), new Iban("DE36100100100000000001")),
                new PaymentView.Holder(new PartyName("Max Mustermann"), new Iban("DE36100100100000000001")),
                new RemittanceInformation("Rent"), Instant.parse("2026-11-13T10:00:00Z"),
                Instant.parse("2026-11-13T10:00:01Z"));

        assertThat(json.readValue(json.writeValueAsString(view), PaymentView.class)).isEqualTo(view);
    }
}
