package com.tpe.csmgateway.domain.outgoing;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import java.util.Objects;

/**
 * What a {@code pacs.002} reports for one transaction (specs/15-csm-gateway.md §6.4): {@code ACCP}, {@code ACSC},
 * {@code RJCT} with a reason code, or {@code RJCT} with the proprietary reason {@code UNKNOWN_TX} (the CSM never
 * received the transaction). Anything else is {@link Kind#UNREADABLE}.
 *
 * @param reason the {@code Cd} of a rejection; {@code null} otherwise
 */
public record ReportedStatus(Kind kind, StatusReason reason) {

    public enum Kind { ACCEPTED, SETTLED, REJECTED, UNKNOWN_TRANSACTION, UNREADABLE }

    /** The proprietary reason of the CSM for a transaction it never received (specs/17-csm-simulator.md §3.2). */
    public static final String UNKNOWN_TX = "UNKNOWN_TX";

    public ReportedStatus {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.REJECTED) != (reason != null)) {
            throw new IllegalArgumentException("a reason goes with REJECTED only");
        }
    }

    public static ReportedStatus accepted() {
        return new ReportedStatus(Kind.ACCEPTED, null);
    }

    public static ReportedStatus settled() {
        return new ReportedStatus(Kind.SETTLED, null);
    }

    public static ReportedStatus rejected(StatusReason reason) {
        return new ReportedStatus(Kind.REJECTED, Objects.requireNonNull(reason, "reason"));
    }

    public static ReportedStatus unknownTransaction() {
        return new ReportedStatus(Kind.UNKNOWN_TRANSACTION, null);
    }

    public static ReportedStatus unreadable() {
        return new ReportedStatus(Kind.UNREADABLE, null);
    }

    /**
     * @param txSts the {@code TxSts}
     * @param reasonCode the {@code StsRsnInf/Rsn/Cd}, if any
     * @param proprietaryReason the {@code StsRsnInf/Rsn/Prtry}, if any
     */
    public static ReportedStatus of(String txSts, String reasonCode, String proprietaryReason) {
        return switch (txSts == null ? "" : txSts) {
            case "ACCP" -> accepted();
            case "ACSC" -> settled();
            case "RJCT" -> reasonCode != null ? rejected(ReasonCode.parse(reasonCode))
                    : UNKNOWN_TX.equals(proprietaryReason) ? unknownTransaction() : unreadable();
            default -> unreadable();
        };
    }

    /** The final status this report gives the transfer, or {@code null} if it gives none. */
    public FinalStatus finalStatus() {
        return switch (kind) {
            case SETTLED -> FinalStatus.SETTLED;
            case REJECTED -> FinalStatus.REJECTED;
            case ACCEPTED, UNKNOWN_TRANSACTION, UNREADABLE -> null;
        };
    }
}
