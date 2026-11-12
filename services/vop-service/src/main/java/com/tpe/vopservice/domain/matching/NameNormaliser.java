package com.tpe.vopservice.domain.matching;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The normalisation step of name matching (specs/13-vop-service.md §6.2), applied to the stored holder name and to
 * the name in a verification request alike: Unicode NFKD, diacritics stripped, lower case, {@code ß → ss},
 * punctuation removed, whitespace collapsed, then legal-form and title tokens dropped.
 *
 * <p>Apostrophes join what they stand between ({@code O'Brien → obrien}); any other punctuation or symbol, the dot
 * included, separates words ({@code Müller-Lüdenscheidt → muller ludenscheidt}, {@code Dr.Anna → dr anna}). A
 * configured token is compared without its punctuation ({@code e.K.} is {@code ek}) and dropped as a whole word
 * ({@code Agnes} keeps its {@code ag}), or, at the end of the name, as a run of single letters
 * ({@code S.p.A. → s p a}, {@code e. K. → e k}); single letters elsewhere are initials and stay. Letters without a
 * decomposition ({@code ø}, {@code ł}) stay.
 */
public final class NameNormaliser {

    /** 13 §6.2; {@code tpe.vop.legal-forms} replaces them. */
    public static final List<String> DEFAULT_LEGAL_FORMS = List.of(
            "gmbh", "ag", "kg", "ohg", "sa", "sarl", "bv", "nv", "ltd", "llc", "spa", "srl", "ug", "e.k.");

    /** 13 §6.2; {@code tpe.vop.titles} replaces them. */
    public static final List<String> DEFAULT_TITLES = List.of("dr", "prof", "herr", "frau", "mr", "mrs");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern JOINERS = Pattern.compile("['’]");
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{P}\\p{S}]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private final Set<String> dropped;

    /**
     * @param legalForms legal-form tokens to drop, as written ({@code GmbH}, {@code e.K.})
     * @param titles title tokens to drop, as written
     */
    public NameNormaliser(Collection<String> legalForms, Collection<String> titles) {
        this.dropped = Stream.concat(legalForms.stream(), titles.stream())
                .map(token -> clean(token).replace(" ", ""))
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** With {@link #DEFAULT_LEGAL_FORMS} and {@link #DEFAULT_TITLES}. */
    public static NameNormaliser withDefaults() {
        return new NameNormaliser(DEFAULT_LEGAL_FORMS, DEFAULT_TITLES);
    }

    /** The normalised form of {@code name}: lower-case words separated by single spaces; empty if none remain. */
    public String normalise(String name) {
        List<String> tokens = new ArrayList<>(Arrays.asList(clean(name).split(" ")));
        dropTrailingSpeltOutToken(tokens);
        return tokens.stream()
                .filter(token -> !token.isEmpty() && !dropped.contains(token))
                .collect(Collectors.joining(" "));
    }

    /** {@code S.p.A.}: the trailing run of single letters, if together they are a token to drop. */
    private void dropTrailingSpeltOutToken(List<String> tokens) {
        int start = tokens.size();
        while (start > 0 && tokens.get(start - 1).length() == 1) {
            start--;
        }
        var run = tokens.subList(start, tokens.size());
        if (run.size() > 1 && dropped.contains(String.join("", run))) {
            run.clear();
        }
    }

    /** Every step but the token drop. */
    private static String clean(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        String plain = MARKS.matcher(decomposed).replaceAll("");
        String lower = plain.toLowerCase(Locale.ROOT).replace("ß", "ss");
        String joined = JOINERS.matcher(lower).replaceAll("");
        String separated = SEPARATORS.matcher(joined).replaceAll(" ");
        return WHITESPACE.matcher(separated).replaceAll(" ").strip();
    }
}
