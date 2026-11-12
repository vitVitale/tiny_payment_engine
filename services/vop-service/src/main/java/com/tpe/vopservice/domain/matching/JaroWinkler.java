package com.tpe.vopservice.domain.matching;

/**
 * Jaro-Winkler similarity (Winkler 1990): the Jaro similarity, raised for a common prefix of up to four characters
 * with scale 0.1. Compares code points, so a letter outside the Basic Multilingual Plane counts once. In
 * {@code [0, 1]}; an empty string is 0, as there is nothing to compare.
 */
final class JaroWinkler {

    private static final double PREFIX_SCALE = 0.1;
    private static final int MAX_PREFIX = 4;

    private JaroWinkler() {}

    static double similarity(String first, String second) {
        int[] a = first.codePoints().toArray();
        int[] b = second.codePoints().toArray();
        double jaro = jaro(a, b);
        int prefix = 0;
        while (prefix < Math.min(MAX_PREFIX, Math.min(a.length, b.length)) && a[prefix] == b[prefix]) {
            prefix++;
        }
        return jaro + prefix * PREFIX_SCALE * (1 - jaro);
    }

    private static double jaro(int[] a, int[] b) {
        if (a.length == 0 || b.length == 0) {
            return 0;
        }
        int window = Math.max(0, Math.max(a.length, b.length) / 2 - 1);
        boolean[] matchedA = new boolean[a.length];
        boolean[] matchedB = new boolean[b.length];
        int matches = 0;
        for (int i = 0; i < a.length; i++) {
            int to = Math.min(b.length, i + window + 1);
            for (int j = Math.max(0, i - window); j < to; j++) {
                if (!matchedB[j] && a[i] == b[j]) {
                    matchedA[i] = true;
                    matchedB[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int halfTranspositions = 0;
        for (int i = 0, j = 0; i < a.length; i++) {
            if (matchedA[i]) {
                while (!matchedB[j]) {
                    j++;
                }
                if (a[i] != b[j]) {
                    halfTranspositions++;
                }
                j++;
            }
        }
        double m = matches;
        return (m / a.length + m / b.length + (m - halfTranspositions / 2.0) / m) / 3;
    }
}
