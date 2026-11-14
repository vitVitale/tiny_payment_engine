package com.tpe.paymentapi.application.rejection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.tpe.iso20022model.IsoCodec;
import com.tpe.iso20022model.XsdValidator;
import com.tpe.iso20022model.pain002v10.Document;
import com.tpe.paymentapi.application.mapping.PaymentStatusReport;
import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** specs/10-payment-api.md §3.5, §5.1: the FF01 documents of XML clients, kept 24 h for the client that sent the file. */
class FileRejectionsTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00.123Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryRejections store = new InMemoryRejections();
    private final IsoCodec codec = new IsoCodec();
    private final FileRejections rejections = new FileRejections(store,
            new PaymentStatusReport(codec, new UlidGenerator(clock, new Random(1)), clock), clock,
            Duration.ofHours(24));

    @Test
    void theClientReadsItsRejectionAsPain002RjctFf01() {
        UUID id = rejections.reject("mobile-app", "CLIENT-MSG-0042");

        String xml = rejections.document("mobile-app", id.toString());

        assertThat(new XsdValidator().validate(xml)).isEmpty();
        var group = codec.unmarshal(xml, Document.class).getCstmrPmtStsRpt().getOrgnlGrpInfAndSts();
        assertThat(group.getOrgnlMsgId()).isEqualTo("CLIENT-MSG-0042");
        assertThat(group.getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("FF01");
    }

    @Test
    void anotherClientCannotReadTheRejection() {
        UUID id = rejections.reject("mobile-app", "CLIENT-MSG-0042");

        assertThatExceptionOfType(RejectionNotFoundException.class)
                .isThrownBy(() -> rejections.document("web-banking", id.toString()));
    }

    @Test
    void aRejectionIsKeptForTwentyFourHours() {
        UUID id = rejections.reject("mobile-app", "CLIENT-MSG-0042");

        assertThat(store.expiresAt.get(id)).isEqualTo(NOW.plus(Duration.ofHours(24)));
        clock.set(NOW.plus(Duration.ofHours(24)).plusMillis(1));
        assertThatExceptionOfType(RejectionNotFoundException.class)
                .isThrownBy(() -> rejections.document("mobile-app", id.toString()));
    }

    @Test
    void anUnknownOrMalformedIdIsNotFound() {
        assertThatExceptionOfType(RejectionNotFoundException.class)
                .isThrownBy(() -> rejections.document("mobile-app", UUID.randomUUID().toString()));
        assertThatExceptionOfType(RejectionNotFoundException.class)
                .isThrownBy(() -> rejections.document("mobile-app", "not-a-uuid"));
    }

    @Test
    void thePurgeDeletesTheExpiredRejections() {
        rejections.reject("mobile-app", "CLIENT-MSG-1");
        clock.set(NOW.plus(Duration.ofHours(12)));
        UUID live = rejections.reject("mobile-app", "CLIENT-MSG-2");
        clock.set(NOW.plus(Duration.ofHours(25)));

        assertThat(rejections.purge()).isEqualTo(1);
        assertThat(store.documents).containsOnlyKeys(live);
    }

    private static final class InMemoryRejections implements PaymentRejections {

        final Map<UUID, String> documents = new HashMap<>();
        final Map<UUID, String> clients = new HashMap<>();
        final Map<UUID, Instant> expiresAt = new HashMap<>();

        @Override
        public void store(UUID rejectionId, String clientId, String xml, Instant expires) {
            documents.put(rejectionId, xml);
            clients.put(rejectionId, clientId);
            expiresAt.put(rejectionId, expires);
        }

        @Override
        public Optional<String> find(UUID rejectionId, String clientId, Instant now) {
            return Optional.ofNullable(documents.get(rejectionId))
                    .filter(xml -> clientId.equals(clients.get(rejectionId)))
                    .filter(xml -> !expiresAt.get(rejectionId).isBefore(now));
        }

        @Override
        public int deleteExpired(Instant now, int limit) {
            var expired = expiresAt.entrySet().stream().filter(e -> e.getValue().isBefore(now)).limit(limit)
                    .map(Map.Entry::getKey).toList();
            expired.forEach(id -> {
                documents.remove(id);
                clients.remove(id);
                expiresAt.remove(id);
            });
            return expired.size();
        }
    }
}
