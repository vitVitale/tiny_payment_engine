package com.tpe.vopservice.domain.references;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.domain.matching.VerificationResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The reference check of specs/13-vop-service.md §3.1 (UC-01 4, UC-02 7): a reference is accepted only before it
 * expires and only for the debtor IBAN, payee IBAN and payee name it was issued for. Names compare after trimming and
 * collapsing whitespace, otherwise exactly.
 */
class IssuedReferenceTest {

    private static final Iban DEBTOR = new Iban("DE36100100100000000001");
    private static final Iban PAYEE = new Iban("DE07200200200123456789");
    private static final Instant ISSUED = Instant.parse("2026-11-12T10:00:00.000Z");
    private static final Instant EXPIRES = ISSUED.plus(Duration.ofMinutes(30));

    private final IssuedReference reference = new IssuedReference("VOP-01JAB", DEBTOR, PAYEE, "Anna Schmidt",
            VerificationResult.CLOSE_MATCH, "Anna Schmidt-Berg", new Bic("PEERDEFFXXX"), ISSUED, EXPIRES);

    @Test
    void theValuesItWasIssuedForAreValidBeforeItExpires() {
        assertThat(reference.check(DEBTOR, PAYEE, "Anna Schmidt", EXPIRES.minusMillis(1)))
                .isEqualTo(ReferenceCheck.valid(VerificationResult.CLOSE_MATCH));
    }

    @Test
    void VOP_AC_03_aDifferentDebtorIbanPayeeIbanOrPayeeNameIsAMismatch() {
        Instant now = ISSUED.plusSeconds(60);

        assertThat(reference.check(new Iban("DE89370400440532013000"), PAYEE, "Anna Schmidt", now))
                .isEqualTo(ReferenceCheck.invalid(ReferenceProblem.MISMATCH));
        assertThat(reference.check(DEBTOR, new Iban("DE89370400440532013000"), "Anna Schmidt", now))
                .isEqualTo(ReferenceCheck.invalid(ReferenceProblem.MISMATCH));
        assertThat(reference.check(DEBTOR, PAYEE, "Anna Schmitt", now))
                .isEqualTo(ReferenceCheck.invalid(ReferenceProblem.MISMATCH));
    }

    @Test
    void VOP_AC_03_itIsExpiredFromItsExpiryOn() {
        assertThat(reference.check(DEBTOR, PAYEE, "Anna Schmidt", EXPIRES))
                .isEqualTo(ReferenceCheck.invalid(ReferenceProblem.EXPIRED));
        // an expired reference says nothing about the values it was issued for
        assertThat(reference.check(DEBTOR, PAYEE, "Peter Wagner", EXPIRES.plusSeconds(1)))
                .isEqualTo(ReferenceCheck.invalid(ReferenceProblem.EXPIRED));
    }

    @Test
    void aPayeeNameDifferingOnlyInCaseIsAMismatch() {
        assertThat(reference.check(DEBTOR, PAYEE, "anna schmidt", ISSUED))
                .isEqualTo(ReferenceCheck.invalid(ReferenceProblem.MISMATCH));
        assertThat(reference.check(DEBTOR, PAYEE, "Anna SCHMIDT", ISSUED))
                .isEqualTo(ReferenceCheck.invalid(ReferenceProblem.MISMATCH));
    }

    @Test
    void whitespaceAroundAndBetweenTheWordsDoesNotCount() {
        assertThat(reference.check(DEBTOR, PAYEE, "  Anna \t  Schmidt ", ISSUED))
                .isEqualTo(ReferenceCheck.valid(VerificationResult.CLOSE_MATCH));
        assertThat(reference.check(DEBTOR, PAYEE, "AnnaSchmidt", ISSUED))
                .isEqualTo(ReferenceCheck.invalid(ReferenceProblem.MISMATCH));
    }

    @Test
    void ibansCompareInElectronicFormat() {
        Iban paper = new Iban("de36 1001 0010 0000 0000 01");

        assertThat(reference.check(paper, PAYEE, "Anna Schmidt", ISSUED))
                .isEqualTo(ReferenceCheck.valid(VerificationResult.CLOSE_MATCH));
    }

    @Test
    void payeeNamesCanonicaliseByTrimmingAndCollapsingWhitespaceOnly() {
        assertThat(IssuedReference.canonicalName("  Anna   Schmidt\n")).isEqualTo("Anna Schmidt");
        assertThat(IssuedReference.canonicalName("Müller-Lüdenscheidt")).isEqualTo("Müller-Lüdenscheidt");
    }
}
