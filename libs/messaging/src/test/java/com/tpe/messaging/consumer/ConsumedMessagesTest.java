package com.tpe.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.ContractJson;
import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class ConsumedMessagesTest {

    private static final AcceptCreditTransfer ACCEPT = new AcceptCreditTransfer(new PaymentId(UUID.randomUUID()),
            new TxId("PEER20261021000017"), new Bic("PEERDEFFXXX"), Instant.parse("2026-10-21T09:16:01.125Z"));

    @Test
    void readsTheAcceptedTypeTheHeaderNames() {
        var record = record("AcceptCreditTransfer");

        assertThat(ConsumedMessages.readOneOf(record, RejectCreditTransfer.class, AcceptCreditTransfer.class))
                .isEqualTo(ACCEPT);
    }

    @Test
    void aTypeTheListenerDoesNotAcceptIsUnknown() {
        assertThatThrownBy(() -> ConsumedMessages.readOneOf(record("Bogus"), AcceptCreditTransfer.class))
                .isInstanceOf(UnknownMessageTypeException.class)
                .hasMessage("unknown message type Bogus on sample.events");
    }

    @Test
    void aRecordWithoutTypeHeaderIsUnknown() {
        assertThatThrownBy(() -> ConsumedMessages.readOneOf(record(null), AcceptCreditTransfer.class))
                .isInstanceOfSatisfying(UnknownMessageTypeException.class,
                        e -> assertThat(e.messageType()).isNull());
    }

    private static ConsumerRecord<String, String> record(String messageType) {
        var record = new ConsumerRecord<>("sample.events", 0, 0L, "key", new ContractJson().write(ACCEPT));
        if (messageType != null) {
            record.headers().add("tpe-message-type", messageType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
