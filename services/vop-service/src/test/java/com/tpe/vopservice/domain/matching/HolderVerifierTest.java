package com.tpe.vopservice.domain.matching;

import static com.tpe.vopservice.domain.matching.VerificationResult.CLOSE_MATCH;
import static com.tpe.vopservice.domain.matching.VerificationResult.MATCH;
import static com.tpe.vopservice.domain.matching.VerificationResult.NOT_POSSIBLE;
import static com.tpe.vopservice.domain.matching.VerificationResult.NO_MATCH;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import com.tpe.vopservice.domain.accounts.ProjectedHolder;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The responder's answer for one projected holder (specs/13-vop-service.md §6.2, UC-03): which accounts can be
 * verified, the organisation-id shortcut, and the actual name of a close match.
 */
class HolderVerifierTest {

    private static final String LEI = "529900T8BM49AURSDO55";

    private final NameNormaliser normaliser = NameNormaliser.withDefaults();
    private final HolderVerifier verifier = new HolderVerifier(normaliser, NameMatcher.withDefaults());

    @Test
    void VOP_AC_05_anUnknownIbanIsNotPossible() {
        assertThat(verifier.verify(Optional.empty(), "Anna Schmidt", null))
                .isEqualTo(new VerificationAnswer(NOT_POSSIBLE, null));
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"CLOSED", "UNKNOWN"})
    void VOP_AC_05_aClosedAccountIsNotPossibleEvenForTheExactName(AccountStatus status) {
        var holder = person("Anna Schmidt", status);

        assertThat(verifier.verify(Optional.of(holder), "Anna Schmidt", null))
                .isEqualTo(new VerificationAnswer(NOT_POSSIBLE, null));
    }

    @Test
    void VOP_AC_05_aBlockedAccountIsAnsweredNormally() {
        var blocked = person("Anna Schmidt-Berg", AccountStatus.BLOCKED);

        assertThat(verifier.verify(Optional.of(blocked), "Anna Schmidt-Berg", null))
                .isEqualTo(new VerificationAnswer(MATCH, null));
        assertThat(verifier.verify(Optional.of(blocked), "Anna Schmitt", null))
                .isEqualTo(new VerificationAnswer(CLOSE_MATCH, "Anna Schmidt-Berg"));
        assertThat(verifier.verify(Optional.of(blocked), "Peter Wagner", null))
                .isEqualTo(new VerificationAnswer(NO_MATCH, null));
    }

    @Test
    void aCloseMatchReturnsTheHolderNameAsStored() {
        var holder = person("Dr. Anna Schmidt-Berg", AccountStatus.OPEN);

        assertThat(verifier.verify(Optional.of(holder), "Anna Schmitt", null))
                .isEqualTo(new VerificationAnswer(CLOSE_MATCH, "Dr. Anna Schmidt-Berg"));
    }

    @Test
    void VOP_AC_06_anEqualOrganisationIdMatchesWhateverTheName() {
        var acme = organisation("Acme GmbH", LEI);

        assertThat(verifier.verify(Optional.of(acme), "Globex Corporation", LEI))
                .isEqualTo(new VerificationAnswer(MATCH, null));
        assertThat(verifier.verify(Optional.of(acme), "Globex Corporation", " " + LEI.toLowerCase() + " "))
                .isEqualTo(new VerificationAnswer(MATCH, null));
    }

    @Test
    void VOP_AC_06_aDifferentOrganisationIdIsNoMatchWhateverTheName() {
        var acme = organisation("Acme GmbH", LEI);

        assertThat(verifier.verify(Optional.of(acme), "Acme GmbH", "5493001KJTIIGC8Y1R12"))
                .isEqualTo(new VerificationAnswer(NO_MATCH, null));
    }

    @Test
    void theNameDecidesWhenEitherSideHasNoOrganisationId() {
        var withoutId = organisation("Acme GmbH", null);
        var withId = organisation("Acme GmbH", LEI);
        var person = person("Acme", AccountStatus.OPEN);

        assertThat(verifier.verify(Optional.of(withoutId), "Acme", LEI).result()).isEqualTo(MATCH);
        assertThat(verifier.verify(Optional.of(withId), "Acme", null).result()).isEqualTo(MATCH);
        assertThat(verifier.verify(Optional.of(withId), "Acme", "  ").result()).isEqualTo(MATCH);
        assertThat(verifier.verify(Optional.of(withId), "Globex", null).result()).isEqualTo(NO_MATCH);
        // only an organisation holder takes the shortcut
        assertThat(verifier.verify(Optional.of(person), "Globex", LEI).result()).isEqualTo(NO_MATCH);
    }

    @Test
    void theOrganisationIdShortcutStillNeedsAnAccountThatCanBeVerified() {
        var closed = new ProjectedHolder(new AccountHolder(UUID.randomUUID(), "DE36100100100000000001",
                HolderType.ORGANISATION, "Acme GmbH", LEI, AccountStatus.CLOSED, 3), "acme");

        assertThat(verifier.verify(Optional.of(closed), "Acme", LEI).result()).isEqualTo(NOT_POSSIBLE);
    }

    private ProjectedHolder person(String name, AccountStatus status) {
        return projected(new AccountHolder(UUID.randomUUID(), "DE36100100100000000001", HolderType.PERSON, name,
                null, status, 1));
    }

    private ProjectedHolder organisation(String name, String organisationId) {
        return projected(new AccountHolder(UUID.randomUUID(), "DE36100100100000000001", HolderType.ORGANISATION,
                name, organisationId, AccountStatus.OPEN, 1));
    }

    private ProjectedHolder projected(AccountHolder holder) {
        return new ProjectedHolder(holder, normaliser.normalise(holder.holderName()));
    }
}
