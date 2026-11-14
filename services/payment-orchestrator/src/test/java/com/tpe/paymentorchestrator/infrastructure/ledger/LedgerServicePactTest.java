package com.tpe.paymentorchestrator.infrastructure.ledger;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslResponse;
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
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
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
 * P-04, payment-orchestrator → ledger-service holds (specs/20-testing-strategy.md §10.1), every answer an on-us
 * payment meets: create hold {@code 201} / replay {@code 200} / each refusal the orchestrator maps; settle-on-us
 * {@code 200} / replay / payee refusals / {@code 409 IDEMPOTENCY_CONFLICT}; release {@code 200} / unknown id
 * (tombstone) / {@code 409 HOLD_SETTLED}; through the real {@link HttpLedger}. Settle (non-on-us) and the pending
 * credits follow with M3. Verified by ledger-service's {@code LedgerServicePactVerificationIT} with
 * {@code -Ppact-verify}.
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
        return held(createHold(builder.given("account is OPEN with available balance",
                Map.of("availableMinor", 100_000)), "create hold: created"), 201);
    }

    @Pact(consumer = CONSUMER)
    V4Pact createHoldReplayed(PactDslWithProvider builder) {
        return held(createHold(builder.given("hold is ACTIVE", Map.of("amountMinor", AMOUNT_MINOR)),
                "create hold: a retry answers the existing hold"), 200);
    }

    @Pact(consumer = CONSUMER)
    V4Pact createHoldInsufficientFunds(PactDslWithProvider builder) {
        return refused(createHold(builder.given("account is OPEN with available balance",
                Map.of("availableMinor", 1_000)), "create hold: insufficient funds"), 422, "INSUFFICIENT_FUNDS");
    }

    @Pact(consumer = CONSUMER)
    V4Pact createHoldUnknownPayer(PactDslWithProvider builder) {
        return refused(createHold(builder.given("no account has the payer IBAN"), "create hold: unknown payer"), 422,
                "ACCOUNT_NOT_FOUND");
    }

    @Pact(consumer = CONSUMER)
    V4Pact createHoldBlockedPayer(PactDslWithProvider builder) {
        return refused(createHold(builder.given("payer account is BLOCKED"), "create hold: blocked payer"), 422,
                "ACCOUNT_BLOCKED");
    }

    @Pact(consumer = CONSUMER)
    V4Pact createHoldClosedPayer(PactDslWithProvider builder) {
        return refused(createHold(builder.given("payer account is CLOSED"), "create hold: closed payer"), 422,
                "ACCOUNT_CLOSED");
    }

    @Pact(consumer = CONSUMER)
    V4Pact createHoldWithoutCustomerNumber(PactDslWithProvider builder) {
        return refused(createHold(builder.given("payer account has no customer number",
                Map.of("availableMinor", 100_000)), "create hold: payer without customer number"), 422,
                "CUSTOMER_ID_MISSING");
    }

    @Pact(consumer = CONSUMER)
    V4Pact createHoldAfterRelease(PactDslWithProvider builder) {
        return refused(createHold(builder.given("hold was released", Map.of("availableMinor", 100_000)),
                "create hold: a late create after the release's tombstone"), 409, "HOLD_RELEASED");
    }

    @Pact(consumer = CONSUMER)
    V4Pact settleOnUs(PactDslWithProvider builder) {
        return settled(settleOnUs(builder.given("hold is ACTIVE and the payee account is OPEN",
                Map.of("amountMinor", AMOUNT_MINOR)), "settle on-us: settled"));
    }

    @Pact(consumer = CONSUMER)
    V4Pact settleOnUsReplayed(PactDslWithProvider builder) {
        return settled(settleOnUs(builder.given("hold is SETTLED on-us", Map.of("amountMinor", AMOUNT_MINOR)),
                "settle on-us: a retry answers the settlement"));
    }

    @Pact(consumer = CONSUMER)
    V4Pact settleOnUsUnknownPayee(PactDslWithProvider builder) {
        return refused(settleOnUs(builder.given("hold is ACTIVE and no account has the payee IBAN",
                Map.of("amountMinor", AMOUNT_MINOR)), "settle on-us: unknown payee"), 422, "ACCOUNT_NOT_FOUND");
    }

    @Pact(consumer = CONSUMER)
    V4Pact settleOnUsBlockedPayee(PactDslWithProvider builder) {
        return refused(settleOnUs(builder.given("hold is ACTIVE and the payee account is BLOCKED",
                Map.of("amountMinor", AMOUNT_MINOR)), "settle on-us: blocked payee"), 422, "ACCOUNT_BLOCKED");
    }

    @Pact(consumer = CONSUMER)
    V4Pact settleOnUsClosedPayee(PactDslWithProvider builder) {
        return refused(settleOnUs(builder.given("hold is ACTIVE and the payee account is CLOSED",
                Map.of("amountMinor", AMOUNT_MINOR)), "settle on-us: closed payee"), 422, "ACCOUNT_CLOSED");
    }

    /** The verifier sends a fresh key each time, so the conflict here is the hold settled to another payee. */
    @Pact(consumer = CONSUMER)
    V4Pact settleOnUsToAnotherPayee(PactDslWithProvider builder) {
        return refused(settleOnUs(builder.given("hold is SETTLED on-us to another payee",
                        Map.of("amountMinor", AMOUNT_MINOR)), "settle on-us: the hold settled to another payee"),
                409, "IDEMPOTENCY_CONFLICT");
    }

    @Pact(consumer = CONSUMER)
    V4Pact releaseHold(PactDslWithProvider builder) {
        return released(releaseHold(builder.given("hold is ACTIVE", Map.of("amountMinor", AMOUNT_MINOR)),
                "release hold: released"));
    }

    @Pact(consumer = CONSUMER)
    V4Pact releaseUnknownHold(PactDslWithProvider builder) {
        return released(releaseHold(builder.given("no hold has the id"),
                "release hold: an unknown hold is tombstoned"));
    }

    @Pact(consumer = CONSUMER)
    V4Pact releaseSettledHold(PactDslWithProvider builder) {
        return refused(releaseHold(builder.given("hold is SETTLED on-us", Map.of("amountMinor", AMOUNT_MINOR)),
                "release hold: a settled hold"), 409, "HOLD_SETTLED");
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

    @Test
    @PactTestFor(pactMethod = "createHoldInsufficientFunds")
    void PACT_P04_aHoldBeyondTheAvailableBalanceIsRefused(MockServer server) {
        assertThat(client(server).createHold(payment(), TIMEOUT)).isEqualTo(refusal("INSUFFICIENT_FUNDS"));
    }

    @Test
    @PactTestFor(pactMethod = "createHoldUnknownPayer")
    void PACT_P04_aHoldOnAnUnknownAccountIsRefused(MockServer server) {
        assertThat(client(server).createHold(payment(), TIMEOUT)).isEqualTo(refusal("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @PactTestFor(pactMethod = "createHoldBlockedPayer")
    void PACT_P04_aHoldOnABlockedAccountIsRefused(MockServer server) {
        assertThat(client(server).createHold(payment(), TIMEOUT)).isEqualTo(refusal("ACCOUNT_BLOCKED"));
    }

    @Test
    @PactTestFor(pactMethod = "createHoldClosedPayer")
    void PACT_P04_aHoldOnAClosedAccountIsRefused(MockServer server) {
        assertThat(client(server).createHold(payment(), TIMEOUT)).isEqualTo(refusal("ACCOUNT_CLOSED"));
    }

    @Test
    @PactTestFor(pactMethod = "createHoldWithoutCustomerNumber")
    void PACT_P04_aHoldOnAnAccountWithoutCustomerNumberIsRefused(MockServer server) {
        assertThat(client(server).createHold(payment(), TIMEOUT)).isEqualTo(refusal("CUSTOMER_ID_MISSING"));
    }

    @Test
    @PactTestFor(pactMethod = "createHoldAfterRelease")
    void PACT_P04_aCreateAfterTheReleaseIsRefused(MockServer server) {
        assertThat(client(server).createHold(payment(), TIMEOUT)).isEqualTo(refusal("HOLD_RELEASED"));
    }

    @Test
    @PactTestFor(pactMethod = "settleOnUsUnknownPayee")
    void PACT_P04_anUnknownPayeeIsRefused(MockServer server) {
        assertThat(client(server).settleOnUs(payment(), TIMEOUT)).isEqualTo(refusal("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @PactTestFor(pactMethod = "settleOnUsBlockedPayee")
    void PACT_P04_aBlockedPayeeIsRefused(MockServer server) {
        assertThat(client(server).settleOnUs(payment(), TIMEOUT)).isEqualTo(refusal("ACCOUNT_BLOCKED"));
    }

    @Test
    @PactTestFor(pactMethod = "settleOnUsClosedPayee")
    void PACT_P04_aClosedPayeeIsRefused(MockServer server) {
        assertThat(client(server).settleOnUs(payment(), TIMEOUT)).isEqualTo(refusal("ACCOUNT_CLOSED"));
    }

    @Test
    @PactTestFor(pactMethod = "settleOnUsToAnotherPayee")
    void PACT_P04_aSettlementToAnotherPayeeIsAnIdempotencyConflict(MockServer server) {
        assertThat(client(server).settleOnUs(payment(), TIMEOUT)).isEqualTo(refusal("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @PactTestFor(pactMethod = "releaseHold")
    void PACT_P04_aHoldIsReleased(MockServer server) {
        assertThat(client(server).releaseHold(payment(), TIMEOUT))
                .isEqualTo(new LedgerAnswer.Done<>(new Ledger.Released()));
    }

    @Test
    @PactTestFor(pactMethod = "releaseUnknownHold")
    void PACT_P04_anUnknownHoldIsReleasedAsATombstone(MockServer server) {
        assertThat(client(server).releaseHold(payment(), TIMEOUT))
                .isEqualTo(new LedgerAnswer.Done<>(new Ledger.Released()));
    }

    @Test
    @PactTestFor(pactMethod = "releaseSettledHold")
    void PACT_P04_aSettledHoldIsNotReleased(MockServer server) {
        assertThat(client(server).releaseHold(payment(), TIMEOUT)).isEqualTo(refusal(LedgerRefusal.HOLD_SETTLED));
    }

    private static <T> LedgerAnswer<T> refusal(String code) {
        return new LedgerAnswer.Refused<>(new LedgerRefusal(code));
    }

    private static PactDslResponse createHold(PactDslWithState given, String description) {
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
                .willRespondWith();
    }

    /** Only what the orchestrator reads of a hold. */
    private static V4Pact held(PactDslResponse response, int status) {
        return response.status(status)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> body.uuid("accountId", PAYER_ACCOUNT)).build())
                .toPact(V4Pact.class);
    }

    /** A Problem Details refusal: the orchestrator reads its {@code code}. */
    private static V4Pact refused(PactDslResponse response, int status, String code) {
        return response.status(status)
                .matchHeader("Content-Type", "application/problem\\+json.*", "application/problem+json")
                .body(newJsonBody(body -> body.stringValue("code", code)).build())
                .toPact(V4Pact.class);
    }

    private static PactDslResponse settleOnUs(PactDslWithState given, String description) {
        return given.uponReceiving(description)
                .method("POST")
                .pathFromProviderState("/v1/holds/${holdId}/settle-on-us", "/v1/holds/" + HOLD_ID + "/settle-on-us")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .matchHeader("Idempotency-Key", "hold-settle-on-us:" + UUID_REGEX, "hold-settle-on-us:" + HOLD_ID)
                .body(newJsonBody(body -> body.valueFromProviderState("creditorIban", "${creditorIban}",
                        CREDITOR_IBAN)).build())
                .willRespondWith();
    }

    /** Only what the orchestrator reads of a settlement. */
    private static V4Pact settled(PactDslResponse response) {
        return response.status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> {
                    body.uuid("journalEntryId", ENTRY);
                    body.uuid("creditorAccountId", PAYEE_ACCOUNT);
                }).build())
                .toPact(V4Pact.class);
    }

    private static PactDslResponse releaseHold(PactDslWithState given, String description) {
        return given.uponReceiving(description)
                .method("POST")
                .pathFromProviderState("/v1/holds/${holdId}/release", "/v1/holds/" + HOLD_ID + "/release")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .matchHeader("Idempotency-Key", "hold-release:" + UUID_REGEX, "hold-release:" + HOLD_ID)
                .body("{}", "application/json")
                .willRespondWith();
    }

    /** The release's status is all the orchestrator reads: a tombstone is {@code RELEASED} too. */
    private static V4Pact released(PactDslResponse response) {
        return response.status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> body.stringValue("status", "RELEASED")).build())
                .toPact(V4Pact.class);
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
