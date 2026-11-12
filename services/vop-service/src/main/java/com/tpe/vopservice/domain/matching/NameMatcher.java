package com.tpe.vopservice.domain.matching;

import java.util.Arrays;

/**
 * Scores two names normalised by {@link NameNormaliser} and maps the score to a result (specs/13-vop-service.md
 * §6.2): Jaro-Winkler on the token-sorted strings and on the strings as they are, the higher of the two (word order
 * does not count); score ≥ {@code matchThreshold} → {@code MATCH}, ≥ {@code closeMatchThreshold} →
 * {@code CLOSE_MATCH}, else {@code NO_MATCH}. A name with nothing left after normalisation never matches.
 *
 * <p>Jaro-Winkler weighs a shared prefix, so one wrong letter late in a longer name can still score
 * {@code MATCH} at the default 0.95 ({@code Anna Schmidt} / {@code Anna Schmitt}: 0.967); see
 * {@code name-matching-cases.csv}.
 */
public final class NameMatcher {

    /** 13 §7: {@code tpe.vop.match-threshold}. */
    public static final double DEFAULT_MATCH_THRESHOLD = 0.95;

    /** 13 §7: {@code tpe.vop.close-match-threshold}. */
    public static final double DEFAULT_CLOSE_MATCH_THRESHOLD = 0.85;

    private final double matchThreshold;
    private final double closeMatchThreshold;

    /** Requires {@code 0 < closeMatchThreshold ≤ matchThreshold ≤ 1}. */
    public NameMatcher(double matchThreshold, double closeMatchThreshold) {
        if (!(closeMatchThreshold > 0 && closeMatchThreshold <= matchThreshold && matchThreshold <= 1)) {
            throw new IllegalArgumentException("thresholds must satisfy 0 < close-match-threshold ("
                    + closeMatchThreshold + ") <= match-threshold (" + matchThreshold + ") <= 1");
        }
        this.matchThreshold = matchThreshold;
        this.closeMatchThreshold = closeMatchThreshold;
    }

    /** With {@link #DEFAULT_MATCH_THRESHOLD} and {@link #DEFAULT_CLOSE_MATCH_THRESHOLD}. */
    public static NameMatcher withDefaults() {
        return new NameMatcher(DEFAULT_MATCH_THRESHOLD, DEFAULT_CLOSE_MATCH_THRESHOLD);
    }

    /** {@code MATCH}, {@code CLOSE_MATCH} or {@code NO_MATCH} for two normalised names. */
    public VerificationResult match(String normalisedHolderName, String normalisedName) {
        double score = score(normalisedHolderName, normalisedName);
        if (score >= matchThreshold) {
            return VerificationResult.MATCH;
        }
        return score >= closeMatchThreshold ? VerificationResult.CLOSE_MATCH : VerificationResult.NO_MATCH;
    }

    /** The similarity of two normalised names in {@code [0, 1]}; 0 if either is empty. */
    public double score(String normalisedHolderName, String normalisedName) {
        if (normalisedHolderName.isEmpty() || normalisedName.isEmpty()) {
            return 0;
        }
        return Math.max(
                JaroWinkler.similarity(sorted(normalisedHolderName), sorted(normalisedName)),
                JaroWinkler.similarity(normalisedHolderName, normalisedName));
    }

    private static String sorted(String normalisedName) {
        String[] tokens = normalisedName.split(" ");
        Arrays.sort(tokens);
        return String.join(" ", tokens);
    }
}
