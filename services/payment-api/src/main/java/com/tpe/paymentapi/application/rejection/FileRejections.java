package com.tpe.paymentapi.application.rejection;

import com.tpe.paymentapi.application.mapping.PaymentStatusReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The {@code pain.002 RJCT FF01} an XML client gets for a {@code pain.001} that failed validation
 * (specs/10-payment-api.md §3.5, UC-01 3a): made when the request is refused, kept for {@code retention} (24 h) under
 * a new {@code rejectionId}, readable by the client that sent the document only. Expired ones are purged in chunks,
 * as the idempotency keys are.
 */
public class FileRejections {

    static final int CHUNK = 10_000;

    private final PaymentRejections rejections;
    private final PaymentStatusReport report;
    private final Clock clock;
    private final Duration retention;

    public FileRejections(PaymentRejections rejections, PaymentStatusReport report, Clock clock, Duration retention) {
        this.rejections = rejections;
        this.report = report;
        this.clock = clock;
        this.retention = retention;
    }

    /**
     * @param originalMessageId the document's {@code GrpHdr/MsgId}, {@code NOTPROVIDED} when it cannot be read
     * @return the {@code rejectionId}
     */
    public UUID reject(String clientId, String originalMessageId) {
        UUID rejectionId = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        rejections.store(rejectionId, clientId, report.fileRejection(originalMessageId), now.plus(retention));
        return rejectionId;
    }

    /**
     * @param rejectionId as the client sent it
     * @throws RejectionNotFoundException unknown, expired, another client's, or not a UUID
     */
    public String document(String clientId, String rejectionId) {
        UUID id;
        try {
            id = UUID.fromString(rejectionId);
        } catch (IllegalArgumentException e) {
            throw new RejectionNotFoundException();
        }
        return rejections.find(id, clientId, clock.instant()).orElseThrow(RejectionNotFoundException::new);
    }

    /** @return the number of rejections deleted */
    public int purge() {
        Instant now = clock.instant();
        int total = 0;
        int deleted;
        do {
            deleted = rejections.deleteExpired(now, CHUNK);
            total += deleted;
        } while (deleted == CHUNK);
        return total;
    }
}
