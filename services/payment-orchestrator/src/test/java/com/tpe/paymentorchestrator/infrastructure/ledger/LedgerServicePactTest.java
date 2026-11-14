package com.tpe.paymentorchestrator.infrastructure.ledger;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.dsl.PactDslWithState;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.contracts.ContractsModule;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.domain.payment.PayeeVerification;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-04, payment-orchestrator → ledger-service holds (specs/20-testing-strategy.md §10.1), the part the on-us happy
 * path uses: create hold {@code 201} / replay {@code 200}, settle-on-us {@code 200} / replay, through the real
 * {@link HttpLedger}. The refusals follow with #73. Verified by ledger-service's
 * {@code LedgerServicePactVerificationIT} with {@code -Ppact-verify}.
 *
 * <p>The ledger chooses IBANs and the provider states choose fresh hold ids, so both come from the provider state;
 * the examples here are what the mock server answers to.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = LedgerServicePactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class LedgerServicePactTest {

    static final String CONSUMER = "payment-orchestrator";
    static final String PROVIDER = "ledger-service";

    private static final String HOLD_ID = "0192f0e4-7b1c-7cc3-a1b2-0000000000a1";
    private static final String DEBTOR_IBAN = "DE36100100100000000001";
    private static final String CREDITOR_IBAN = "DE09100100100000000002";
    private static final UUID PAYER_ACCOUNT = UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-0000000000b1");
    private static final UUID PAYEE_ACCOUNT = UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-0000000000b2");
    private static final UUID ENTRY = UUID.fromString("0192f0e4-7b1c-7cc3-a1b2-0000000000c1");
    private static final String TX_ID = "TPE01JABCDEFGHJKMNPQRST00A1";
    private static final int AMOUNT_MINOR = 12_345;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Pact(consumer = CONSUMER)
    V4Pact createHold(PactDslWithProvider builder) {
        return createHold(builder.given("account is OPEN with available balance",
                        Map.of("availableMinor", 100_000)), "create hold: created", 201);
    }

    @Pact(consumer = CONSUMER)
    V4Pact createHoldReplayed(PactDslWithProvider builder) {
        return createHold(builder.given("hold is ACTIVE", Map.of("amountMinor", AMOUNT_MINOR)),
                "create hold: a retry answers the existing hold", 200);
    }

    @Pact(consumer = CONSUMER)
    V4Pact settleOnUs(PactDslWithProvider builder) {
        return settleOnUs(builder.given("hold is ACTIVE and the payee account is OPEN",
                        Map.of("amountMinor", AMOUNT_MINOR)), "settle on-us: settled");
    }

    @Pact(consumer = CONSUMER)
    V4Pact settleOnUsReplayed(PactDslWithProvider builder) {
        return settleOnUs(builder.given("hold is SETTLED on-us", Map.of("amountMinor", AMOUNT_MINOR)),
                "settle on-us: a retry answers the settlement");
    }

    @Test
    @PactTestFor(pactMethod = "createHold")
    void PACT_P04_aHoldIsCreatedOnThePayersAccount(MockServer server) {
        assertThat(client(server).createHold(payment(), TIMEOUT))
                .isEqualTo(new LedgerAnswer.Done<>(new Ledger.HoldCreated(PAYER_ACCOUNT)));
    }

    @Test
    @PactTestFor(pactMethod = "createHoldReplayed")
    void PACT_P04_aRetriedCreateHoldAnswersTheExistingHold(MockServer server) {
        assertThat(client(server).createHold(payment(), TIMEOUT))
                .isEqualTo(new LedgerAnswer.Done<>(new Ledger.HoldCreated(PAYER_ACCOUNT)));
    }

    @Test
    @PactTestFor(pactMethod = "settleOnUs")
    void PACT_P04_anOnUsSettlementBooksTheEntryToThePayee(MockServer server) {
        assertThat(client(server).settleOnUs(payment(), TIMEOUT))
                .isEqualTo(new LedgerAnswer.Done<>(new Ledger.SettledOnUs(ENTRY, PAYEE_ACCOUNT)));
    }

    @Test
    @PactTestFor(pactMethod = "settleOnUsReplayed")
    void PACT_P04_aRetriedOnUsSettlementAnswersTheSettlement(MockServer server) {
        assertThat(client(server).settleOnUs(payment(), TIMEOUT))
                .isEqualTo(new LedgerAnswer.Done<>(new Ledger.SettledOnUs(ENTRY, PAYEE_ACCOUNT)));
    }

    private static V4Pact createHold(PactDslWithState given, String description,
            int status) {
        return given.uponReceiving(description)
                .method("POST").path("/v1/holds")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .matchHeader("Idempotency-Key", "hold-create:" + UUID_REGEX, "hold-create:" + HOLD_ID)
                .body(newJsonBody(body -> {
                    body.valueFromProviderState("holdId", "${holdId}", HOLD_ID);
                    body.valueFromProviderState("debtorIban", "${debtorIban}", DEBTOR_IBAN);
                    body.integerType("amountMinor", AMOUNT_MINOR);
                    body.stringValue("currency", "EUR");
                    body.object("reference", reference -> {
                        reference.valueFromProviderState("paymentId", "${holdId}", HOLD_ID);
                        reference.stringMatcher("txId", "[A-Za-z0-9]{1,35}", TX_ID);
                    });
                }).build())
                .willRespondWith()
                .status(status)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                // only what the orchestrator reads
                .body(newJsonBody(body -> body.uuid("accountId", PAYER_ACCOUNT)).build())
                .toPact(V4Pact.class);
    }

    private static V4Pact settleOnUs(PactDslWithState given, String description) {
        return given.uponReceiving(description)
                .method("POST")
                .pathFromProviderState("/v1/holds/${holdId}/settle-on-us", "/v1/holds/" + HOLD_ID + "/settle-on-us")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .matchHeader("Idempotency-Key", "hold-settle-on-us:" + UUID_REGEX, "hold-settle-on-us:" + HOLD_ID)
                .body(newJsonBody(body -> body.valueFromProviderState("creditorIban", "${creditorIban}",
                        CREDITOR_IBAN)).build())
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(settlement())
                .toPact(V4Pact.class);
    }

    /** Only what the orchestrator reads. */
    private static DslPart settlement() {
        return newJsonBody(body -> {
            body.uuid("journalEntryId", ENTRY);
            body.uuid("creditorAccountId", PAYEE_ACCOUNT);
        }).build();
    }

    private static HttpLedger client(MockServer server) {
        JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();
        return new HttpLedger(RestClient.builder()
                .baseUrl(server.getUrl())
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(json)))
                .build(), Duration.ofSeconds(1));
    }

    /** The example payment: on-us, its id the hold id. */
    private static Payment payment() {
        Instant accepted = Instant.parse("2026-11-14T10:00:00Z");
        return new Payment(new PaymentId(UUID.fromString(HOLD_ID)), PaymentDirection.OUTGOING,
                PaymentState.RISK_CHECKED, null, true, new CommandId(UUID.randomUUID()), "mobile-app", "0".repeat(64),
                new TxId(TX_ID), new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"), new EndToEndId("INV-2026-0042"),
                new Money(AMOUNT_MINOR, Currency.getInstance("EUR")),
                new PaymentParty(new PartyName("Anna Schmidt"), new Iban(DEBTOR_IBAN), new Bic("TPEBDEFFXXX")),
                new PaymentParty(new PartyName("Max Mustermann"), new Iban(CREDITOR_IBAN), new Bic("TPEBDEFFXXX")),
                new RemittanceInformation("Invoice 42"), "VOP-1", PayeeVerification.MATCH, accepted,
                accepted.plusSeconds(10), accepted.plusSeconds(20), PendingAction.CREATE_HOLD, null, null, null, null,
                null);
    }
}
