package com.tpe.vopservice.application.metrics;

import com.tpe.vopservice.domain.matching.VerificationResult;

/**
 * The timer {@code tpe.vop.requests{role,route,result,cause}} (specs/13-vop-service.md §8, 25 §11), the SLI of
 * SLO-VOP-02 to -04: how long a verification took and how it ended.
 */
public interface VopRequestMetrics {

    /** Starts timing one request; {@link Timing#stop} records it. */
    Timing start();

    interface Timing {

        void stop(Role role, Route route, VerificationResult result, Cause cause);
    }

    /** Who asked: a payer of ours ({@code REQUESTER}) or another PSP through the scheme endpoint. */
    enum Role { REQUESTER, RESPONDER }

    /** Who answered: our own projection or a counterparty PSP. */
    enum Route { OWN, REMOTE }

    /**
     * Why a result is {@code NOT_POSSIBLE} (25 §5.4, SLO-VOP-03): a cause of ours ({@code LOCAL}, e.g. no
     * counterparty client yet) or of the counterparty ({@code EXTERNAL}: timeout, error); {@code NONE} for every
     * answer the responder gave.
     */
    enum Cause { LOCAL, EXTERNAL, NONE }
}
