package com.tpe.riskservice.api.accounts;

import com.tpe.contracts.AccountBlocked;
import com.tpe.contracts.AccountClosed;
import com.tpe.contracts.AccountEvent;
import com.tpe.contracts.AccountOpened;
import com.tpe.contracts.AccountUnblocked;
import com.tpe.messaging.consumer.ConsumedMessages;
import com.tpe.riskservice.application.accounts.AccountHolderProjection;
import com.tpe.riskservice.domain.accounts.AccountHolder;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The account events listener (specs/14-risk-service.md §4): {@code accounts.events}, group {@code risk-service}. A
 * snapshot is read and validated ({@link ConsumedMessages#readOneOf}, so an invalid one goes to the DLT) and handed on
 * as an {@link AccountHolder}; a {@code null} value is the account's tombstone (ADR-007).
 */
@Component
class AccountEventsListener {

    private final AccountHolderProjection projection;

    AccountEventsListener(AccountHolderProjection projection) {
        this.projection = projection;
    }

    @KafkaListener(topics = AccountHolderProjection.TOPIC)
    void on(ConsumerRecord<String, String> record) {
        UUID eventId = ConsumedMessages.eventId(record);
        if (record.value() == null) {
            projection.remove(eventId, UUID.fromString(record.key()));
            return;
        }
        var event = (AccountEvent) ConsumedMessages.readOneOf(record,
                AccountOpened.class, AccountBlocked.class, AccountUnblocked.class, AccountClosed.class);
        projection.apply(eventId, holder(event));
    }

    private static AccountHolder holder(AccountEvent event) {
        var account = event.account();
        var holder = account.holder();
        return new AccountHolder(
                event.accountId(),
                account.customerId(),
                account.iban().value(),
                AccountHolder.HolderType.valueOf(holder.type().name()),
                holder.name().value(),
                holder.dateOfBirth(),
                holder.countryOfResidence(),
                holder.organisationId(),
                AccountHolder.AccountStatus.valueOf(account.status().name()),
                account.openedAt(),
                event.revision());
    }
}
