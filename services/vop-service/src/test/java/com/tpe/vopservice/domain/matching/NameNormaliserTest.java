package com.tpe.vopservice.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The normalisation step of name matching (specs/13-vop-service.md §6.2): NFKD, diacritics stripped, lower case,
 * {@code ß → ss}, punctuation removed, whitespace collapsed, legal-form and title tokens dropped. A legal form
 * spelt out letter by letter ({@code S.p.A.}, {@code e. K.}) at the end of a name is dropped too; initials elsewhere
 * stay.
 */
class NameNormaliserTest {

    private final NameNormaliser normaliser = NameNormaliser.withDefaults();

    @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
    @CsvSource(delimiter = '|', textBlock = """
            Anna Schmidt                 | anna schmidt
            ANNA SCHMIDT                 | anna schmidt
            Jürgen Müller                | jurgen muller
            François Lefèvre             | francois lefevre
            Ångström Øberg               | angstrom øberg
            José Núñez                   | jose nunez
            Gerd Strauß                  | gerd strauss
            STRAUẞ                       | strauss
            ﬁnance Ltd                   | finance
            Müller-Lüdenscheidt          | muller ludenscheidt
            O'Brien                      | obrien
            O’Brien                      | obrien
            Smith, John                  | smith john
            Smith & Jones                | smith jones
            (Anna) Schmidt               | anna schmidt
            '  Anna   Schmidt  '         | anna schmidt
            Acme GmbH                    | acme
            ACME GMBH                    | acme
            Acme GmbH & Co. KG           | acme co
            Beispiel e.K.                | beispiel
            Beispiel e. K.               | beispiel
            Dupont S.à r.l.              | dupont
            Société Générale S.A.        | societe generale
            Dupont SARL                  | dupont
            Van Dijk B.V.                | van dijk
            Rossi S.p.A.                 | rossi
            Widget UG                    | widget
            Widget Ltd.                  | widget
            Widget LLC                   | widget
            Dr. Anna Schmidt             | anna schmidt
            Dr.Anna Schmidt              | anna schmidt
            A.Schmidt                    | a schmidt
            J. R. R. Tolkien             | j r r tolkien
            Prof. Dr. Hans Meier         | hans meier
            Herr Hans Meier              | hans meier
            Frau Anna Schmidt            | anna schmidt
            Mr John Smith                | john smith
            Mrs. Jane Smith              | jane smith
            Agnes Kagel                  | agnes kagel
            Drake Mrsic                  | drake mrsic
            GmbH                         | ''
            """)
    void normalises(String name, String expected) {
        assertThat(normaliser.normalise(name)).isEqualTo(expected);
    }

    @Test
    void whitespaceOfAnyKindCollapsesToOneSpace() {
        assertThat(normaliser.normalise("Anna\tSchmidt\n Meier\u00A0Junior")).isEqualTo("anna schmidt meier junior");
    }

    @Test
    void configuredTokensAreNormalisedLikeNames() {
        var normaliser = new NameNormaliser(List.of("S.E.", "Pty"), List.of("Sir"));

        assertThat(normaliser.normalise("Sir Acme Pty SE")).isEqualTo("acme");
        assertThat(normaliser.normalise("Acme S.E.")).isEqualTo("acme");
        assertThat(normaliser.normalise("Acme GmbH")).isEqualTo("acme gmbh");
    }
}
