package com.tpe.csmgateway.application;

import com.tpe.csmgateway.application.journal.MessageJournal.Direction;
import java.time.Duration;

/**
 * The gateway's business metrics (specs/15-csm-gateway.md §8): {@code tpe.csm.messages{direction,type}},
 * {@code tpe.csm.notsent{reason}}, {@code tpe.csm.answer{status,decidedBy}}, {@code tpe.csm.answer.margin},
 * {@code tpe.csm.verdict.ignored}, {@code tpe.csm.transmit.attempts{type}} and {@code tpe.csm.uncorrelated}, the
 * last the cause of an alert (25 §9.2).
 */
public interface GatewayMetrics {

    /** @param type the message version, e.g. {@code pacs.008.001.08} */
    void message(Direction direction, String type);

    /** @param reason {@code AB03} or {@code FF01} */
    void notSent(String reason);

    /**
     * An answer decided for an incoming transfer, {@code tpe.csm.answer{status,decidedBy}}.
     *
     * @param status {@code ACCP} or {@code RJCT}
     * @param decidedBy {@code VERDICT}, {@code DEADLINE} or {@code VALIDATION}
     */
    void answer(String status, String decidedBy);

    /**
     * {@code tpe.csm.answer.margin}: how long before its deadline an answer was decided; a negative margin (a deadline
     * answer decided a poll after it) is not recorded.
     */
    void answerMargin(Duration margin);

    /** {@code tpe.csm.verdict.ignored}: a verdict that came too late, second, or for no known transfer. */
    void verdictIgnored();

    /** One claimed transmission attempt; @param type the message version, e.g. {@code pacs.008.001.08} */
    void transmitAttempt(String type);

    /** A {@code pacs.002}, or a status request, that names no transaction the gateway knows. */
    void uncorrelated();
}
