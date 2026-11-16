package com.tpe.csmgateway.application;

import com.tpe.csmgateway.application.journal.MessageJournal.Direction;

/**
 * The gateway's business metrics (specs/15-csm-gateway.md §8): {@code tpe.csm.messages{direction,type}},
 * {@code tpe.csm.notsent{reason}} and {@code tpe.csm.uncorrelated}, the last the cause of an alert (25 §9.2).
 */
public interface GatewayMetrics {

    /** @param type the message version, e.g. {@code pacs.008.001.08} */
    void message(Direction direction, String type);

    /** @param reason {@code AB03} or {@code FF01} */
    void notSent(String reason);

    void uncorrelated();
}
