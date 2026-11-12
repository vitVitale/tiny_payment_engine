package com.tpe.vopservice.api.accounts;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.LambdaDslObject;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.contracts.AccountKind;
import com.tpe.contracts.AccountStatus;
import com.tpe.contracts.BlockReason;
import com.tpe.vopservice.application.accounts.AccountHolderProjection;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * M-04, consumer vop-service (specs/20-testing-strategy.md §10.1, §10.2): the {@code accounts.events} snapshots of
 * ledger-service through the real listener mapping and {@code ContractValidator} up to the projection, which is
 * mocked. One interaction per message type, and the organisation branch; the body pins the fields vop reads and the
 * ones the contract requires, enums exact and the rest by type. Tombstones cannot be a Pact message
 * (§10.1); {@code AccountEventsIT} covers them.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = LedgerServicePactTest.PROVIDER, providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V4)
class LedgerServicePactTest {

    static final String CONSUMER = "vop-service";
    static final String PROVIDER = "ledger-service";

    private static final UUID ACCOUNT = UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");
    private static final UUID EVENT = UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d11");
    private static final String IBAN = "DE89370400440532013000";
    private static final String IBAN_PATTERN = "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}";
    private static final String TIMESTAMP = "2026-11-02T09:15:00.123Z";
    private static final String LEI = "529900T8BM49AURSDO55";

    private final AccountHolderProjection projection = mock(AccountHolderProjection.class);
    private final AccountEventsListener listener = new AccountEventsListener(projection);

    @Pact(consumer = CONSUMER)
    V4Pact accountOpened(MessagePactBuilder builder) {
        return snapshot(builder, "AccountOpened: a person's account", new Snapshot("AccountOpened", 1,
                AccountStatus.OPEN, List.of()), LedgerServicePactTest::person, event -> {});
    }

    @Pact(consumer = CONSUMER)
    V4Pact organisationAccountOpened(MessagePactBuilder builder) {
        return snapshot(builder, "AccountOpened: an organisation's account", new Snapshot("AccountOpened", 1,
                AccountStatus.OPEN, List.of()),
                holder -> {
                    holder.stringValue("type", "ORGANISATION");
                    holder.stringType("name", "ACME GmbH");
                    holder.stringType("organisationId", LEI);
                },
                event -> {});
    }

    @Pact(consumer = CONSUMER)
    V4Pact accountBlocked(MessagePactBuilder builder) {
        return snapshot(builder, "AccountBlocked: an operator block", new Snapshot("AccountBlocked", 2,
                AccountStatus.BLOCKED, List.of(BlockReason.OPERATOR)), LedgerServicePactTest::person,
                event -> event.stringValue("blockReason", BlockReason.OPERATOR.name()));
    }

    @Pact(consumer = CONSUMER)
    V4Pact accountUnblocked(MessagePactBuilder builder) {
        return snapshot(builder, "AccountUnblocked: the last block lifted", new Snapshot("AccountUnblocked", 3,
                AccountStatus.OPEN, List.of()), LedgerServicePactTest::person,
                event -> event.stringValue("liftedReason", BlockReason.OPERATOR.name()));
    }

    @Pact(consumer = CONSUMER)
    V4Pact accountClosed(MessagePactBuilder builder) {
        return snapshot(builder, "AccountClosed: an empty account closed", new Snapshot("AccountClosed", 2,
                AccountStatus.CLOSED, List.of()), LedgerServicePactTest::person, event -> {});
    }

    @Test
    @PactTestFor(pactMethod = "accountOpened")
    void PACT_M04_accountOpened(V4Interaction.AsynchronousMessage message) {
        assertThat(applied(message)).isEqualTo(new AccountHolder(ACCOUNT, IBAN, HolderType.PERSON, "Anna Schmidt",
                null, AccountHolder.AccountStatus.OPEN, 1));
    }

    @Test
    @PactTestFor(pactMethod = "organisationAccountOpened")
    void PACT_M04_organisationAccountOpened(V4Interaction.AsynchronousMessage message) {
        assertThat(applied(message)).isEqualTo(new AccountHolder(ACCOUNT, IBAN, HolderType.ORGANISATION, "ACME GmbH",
                LEI, AccountHolder.AccountStatus.OPEN, 1));
    }

    @Test
    @PactTestFor(pactMethod = "accountBlocked")
    void PACT_M04_accountBlocked(V4Interaction.AsynchronousMessage message) {
        assertThat(applied(message)).extracting(AccountHolder::status, AccountHolder::revision)
                .containsExactly(AccountHolder.AccountStatus.BLOCKED, 2L);
    }

    @Test
    @PactTestFor(pactMethod = "accountUnblocked")
    void PACT_M04_accountUnblocked(V4Interaction.AsynchronousMessage message) {
        assertThat(applied(message)).extracting(AccountHolder::status, AccountHolder::revision)
                .containsExactly(AccountHolder.AccountStatus.OPEN, 3L);
    }

    @Test
    @PactTestFor(pactMethod = "accountClosed")
    void PACT_M04_accountClosed(V4Interaction.AsynchronousMessage message) {
        assertThat(applied(message)).extracting(AccountHolder::status, AccountHolder::revision)
                .containsExactly(AccountHolder.AccountStatus.CLOSED, 2L);
    }

    /** The message as the relay publishes it, through the listener: what reached the projection. */
    private AccountHolder applied(V4Interaction.AsynchronousMessage message) {
        var record = new ConsumerRecord<>(AccountHolderProjection.TOPIC, 0, 0L, ACCOUNT.toString(),
                message.getContents().getContents().valueAsString());
        message.getContents().getMetadata().forEach((name, value) ->
                record.headers().add(name, String.valueOf(value).getBytes(StandardCharsets.UTF_8)));

        listener.on(record);

        var holder = ArgumentCaptor.forClass(AccountHolder.class);
        verify(projection).apply(eq(EVENT), holder.capture());
        return holder.getValue();
    }

    /** What sets one snapshot message apart: its type, revision, account status and active block reasons. */
    private record Snapshot(String type, int revision, AccountStatus status, List<BlockReason> blockReasons) {}

    /**
     * The message as vop reads it: the fields of {@link AccountEventsListener} plus those {@code ContractValidator}
     * requires, enums exact, the rest by type; {@code typeFields} adds the fields of the message type.
     */
    private static V4Pact snapshot(MessagePactBuilder builder, String description, Snapshot snapshot,
            Consumer<LambdaDslObject> holder, Consumer<LambdaDslObject> typeFields) {
        return builder
                .expectsToReceive(description)
                .withMetadata(metadata -> metadata
                        .add("tpe-message-type", snapshot.type())
                        .add("tpe-schema-version", "1")
                        .uuid("tpe-event-id", EVENT))
                .withContent(newJsonBody(event -> {
                    event.uuid("accountId", ACCOUNT);
                    event.integerType("revision", snapshot.revision());
                    event.stringType("occurredAt", TIMESTAMP);
                    event.object("account", account -> {
                        account.stringMatcher("iban", IBAN_PATTERN, IBAN);
                        account.stringType("customerId", "CUST-000123");
                        account.stringValue("status", snapshot.status().name());
                        account.array("activeBlockReasons", reasons ->
                                snapshot.blockReasons().forEach(reason -> reasons.stringValue(reason.name())));
                        account.stringValue("kind", AccountKind.CUSTOMER.name());
                        account.object("holder", holder::accept);
                        account.stringType("openedAt", TIMESTAMP);
                    });
                    typeFields.accept(event);
                }).build())
                .toPact(V4Pact.class);
    }

    private static void person(LambdaDslObject holder) {
        holder.stringValue("type", "PERSON");
        holder.stringType("name", "Anna Schmidt");
    }
}
