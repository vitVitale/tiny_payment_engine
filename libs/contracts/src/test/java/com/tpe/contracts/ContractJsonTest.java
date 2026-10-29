package com.tpe.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.ContractViolationException.Violation;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ContractJsonTest {

    private final ContractJson json = new ContractJson();
    private final JsonMapper plain = JsonMapper.builder().build();

    @Test
    void badIbanIsAContractViolationThatDoesNotQuoteIt() {
        String sample = json.write(Samples.sendCreditTransfer())
                .replace("DE89370400440532013000", "DE00370400440532013000");

        assertThatThrownBy(() -> json.read(sample, SendCreditTransfer.class))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> {
                    assertThat(e.violations()).containsExactly(new Violation("debtor.iban", "INVALID_IBAN"));
                    assertThat(e.getMessage()).doesNotContain("DE00370400440532013000");
                });
    }

    @Test
    void nonEurCurrencyIsAContractViolation() {
        String sample = json.write(Samples.sendCreditTransfer()).replace("\"EUR\"", "\"USD\"");

        assertThatThrownBy(() -> json.read(sample, SendCreditTransfer.class))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .extracting(Violation::code).containsExactly("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void badBicIsAContractViolation() {
        String sample = json.write(Samples.acceptCreditTransfer()).replace("PEERDEFFXXX", "peer");

        assertThatThrownBy(() -> json.read(sample, AcceptCreditTransfer.class))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactly(new Violation("debtorAgentBic", "INVALID_BIC")));
    }

    @Test
    void missingRequiredFieldIsAContractViolation() {
        ObjectNode sample = (ObjectNode) plain.readTree(json.write(Samples.sendCreditTransfer()));
        sample.remove("uetr");

        assertThatThrownBy(() -> json.read(plain.writeValueAsString(sample), SendCreditTransfer.class))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactly(new Violation("uetr", ContractViolationException.REQUIRED)));
    }

    @Test
    void missingAmountIsAContractViolation() {
        ObjectNode sample = (ObjectNode) plain.readTree(json.write(Samples.sendCreditTransfer()));
        sample.remove("amountMinor");
        sample.remove("currency");

        assertThatThrownBy(() -> json.read(plain.writeValueAsString(sample), SendCreditTransfer.class))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void sendCreditTransferOutOfOrderIsRejectedOnRead() {
        String sample = json.write(Samples.sendCreditTransfer())
                .replace("2026-10-21T09:15:40.000Z", "2026-10-21T09:15:55.000Z");

        assertThatThrownBy(() -> json.read(sample, SendCreditTransfer.class))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .extracting(Violation::code).containsExactly(ContractViolationException.OUT_OF_ORDER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"paymentId\": 42}", "[]", "null", "{\"decidedAt\": \"yesterday\"}"})
    void malformedJsonIsAContractViolation(String sample) {
        assertThatThrownBy(() -> json.read(sample, AcceptCreditTransfer.class))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void writeRejectsAnInvalidMessage() {
        RequestPaymentStatus r = Samples.requestPaymentStatus();
        var invalid = new RequestPaymentStatus(r.paymentId(), r.txId(), r.debtorAgentBic(), r.direction(), 0,
                r.requestedAt());

        assertThatThrownBy(() -> json.write(invalid)).isInstanceOf(ContractViolationException.class);
    }

    @Test
    void instantsAreWrittenAsUtcWithMilliseconds() {
        AcceptCreditTransfer a = Samples.acceptCreditTransfer();
        var message = new AcceptCreditTransfer(a.paymentId(), a.txId(), a.debtorAgentBic(),
                Instant.parse("2026-10-21T09:16:01Z"));

        assertThat(json.write(message)).contains("\"decidedAt\":\"2026-10-21T09:16:01.000Z\"");
    }

    @Test
    void instantsWithAnOffsetAreReadAsUtc() {
        String sample = json.write(Samples.acceptCreditTransfer())
                .replace("2026-10-21T09:16:01.125Z", "2026-10-21T11:16:01.125+02:00");

        assertThat(json.read(sample, AcceptCreditTransfer.class)).isEqualTo(Samples.acceptCreditTransfer());
    }

    @Test
    void unknownReasonCodeIsKeptAsReceived() {
        String sample = json.write(Samples.rejectCreditTransfer()).replace("\"AC06\"", "\"BE05\"");

        RejectCreditTransfer read = json.read(sample, RejectCreditTransfer.class);

        assertThat(read.reasonCode()).isEqualTo(new ReasonCode.Other("BE05"));
        assertThat(json.write(read)).contains("\"reasonCode\":\"BE05\"");
    }

    @Test
    void unknownDirectionIsReadAsUnknown() {
        String sample = json.write(Samples.requestPaymentStatus()).replace("\"OUTGOING\"", "\"SIDEWAYS\"");

        assertThat(json.read(sample, RequestPaymentStatus.class).direction()).isEqualTo(Direction.UNKNOWN);
    }

    @Test
    void producerNeverWritesTheUnknownFallback() {
        RequestPaymentStatus r = Samples.requestPaymentStatus();
        var unknown = new RequestPaymentStatus(r.paymentId(), r.txId(), r.debtorAgentBic(), Direction.UNKNOWN,
                r.attempt(), r.requestedAt());

        assertThatThrownBy(() -> json.write(unknown))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactly(new Violation("direction", ContractViolationException.UNKNOWN_CONSTANT)));
    }

    @Test
    void missingAttemptIsRequiredNotZero() {
        ObjectNode sample = (ObjectNode) plain.readTree(json.write(Samples.requestPaymentStatus()));
        sample.remove("attempt");

        assertThatThrownBy(() -> json.read(plain.writeValueAsString(sample), RequestPaymentStatus.class))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactly(new Violation("attempt", ContractViolationException.REQUIRED)));
    }

    static Stream<Arguments> enumFields() {
        return Stream.of(
                unknownConstant(Samples.answerSent(), "/status", m -> ((AnswerSent) m).status()),
                unknownConstant(Samples.answerSent(), "/decidedBy", m -> ((AnswerSent) m).decidedBy()),
                unknownConstant(Samples.creditTransferSettled(), "/source",
                        m -> ((CreditTransferSettled) m).source()),
                unknownConstant(Samples.paymentSettled(), "/direction", m -> ((PaymentSettled) m).direction()),
                unknownConstant(Samples.accountBlocked(), "/blockReason", m -> ((AccountBlocked) m).blockReason()),
                unknownConstant(Samples.accountBlocked(), "/account/activeBlockReasons/0",
                        m -> ((AccountBlocked) m).account().activeBlockReasons().getFirst()),
                unknownConstant(Samples.accountBlocked(), "/account/status",
                        m -> ((AccountBlocked) m).account().status()),
                unknownConstant(Samples.accountBlocked(), "/account/kind",
                        m -> ((AccountBlocked) m).account().kind()),
                unknownConstant(Samples.accountBlocked(), "/account/holder/type",
                        m -> ((AccountBlocked) m).account().holder().type()));
    }

    private static Arguments unknownConstant(Message sample, String pointer, Function<Message, Enum<?>> field) {
        return Arguments.of(Named.of(sample.type() + pointer, sample), pointer, field);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enumFields")
    void unknownEnumConstantIsReadAsUnknown(Message sample, String pointer, Function<Message, Enum<?>> field) {
        JsonNode tree = plain.readTree(json.write(sample));
        JsonPointer at = JsonPointer.compile(pointer);
        JsonNode parent = tree.at(at.head());
        if (parent instanceof ArrayNode array) {
            array.set(Integer.parseInt(at.last().getMatchingProperty()), "ADDED_LATER");
        } else {
            ((ObjectNode) parent).put(at.last().getMatchingProperty(), "ADDED_LATER");
        }

        Message read = json.read(plain.writeValueAsString(tree), sample.getClass());

        assertThat(field.apply(read).name()).isEqualTo("UNKNOWN");
    }

    @Test
    void producerNeverWritesUnknownInNestedFields() {
        AccountBlocked b = Samples.accountBlocked();
        AccountSnapshot a = b.account();
        var account = new AccountSnapshot(a.iban(), a.customerId(), AccountStatus.UNKNOWN,
                List.of(BlockReason.UNKNOWN), a.kind(), a.holder(), a.openedAt(), a.closedAt());

        assertThatThrownBy(() -> json.write(Samples.withAccount(b, account)))
                .isInstanceOfSatisfying(ContractViolationException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(
                                new Violation("account.status", ContractViolationException.UNKNOWN_CONSTANT),
                                new Violation("account.activeBlockReasons",
                                        ContractViolationException.UNKNOWN_CONSTANT)));
    }

    @Test
    void datesAreWrittenAsIsoLocalDates() {
        assertThat(plain.readTree(json.write(Samples.accountOpened())).at("/account/holder/dateOfBirth").asString())
                .isEqualTo("1985-04-12");
    }

    @Test
    void absentOptionalFieldsAreNotWritten() {
        assertThat(plain.readTree(json.write(Samples.sendCreditTransfer())).get("creditor").has("accountId")).isFalse();
    }
}
