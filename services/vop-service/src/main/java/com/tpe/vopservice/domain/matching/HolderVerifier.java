package com.tpe.vopservice.domain.matching;

import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import com.tpe.vopservice.domain.accounts.ProjectedHolder;
import java.util.Locale;
import java.util.Optional;

/**
 * Steps 1–3 of responding (specs/13-vop-service.md §6.2, UC-03): an unknown IBAN or an account that cannot be
 * verified ({@code CLOSED}, or a status a newer ledger added) is {@code NOT_POSSIBLE}; a {@code BLOCKED} one is
 * answered like an open one, so blocking is not disclosed. When the request carries an organisation id and the holder
 * is an organisation with one, the ids decide (compared trimmed, ignoring case); otherwise the names do.
 */
public final class HolderVerifier {

    private final NameNormaliser normaliser;
    private final NameMatcher matcher;

    public HolderVerifier(NameNormaliser normaliser, NameMatcher matcher) {
        this.normaliser = normaliser;
        this.matcher = matcher;
    }

    /**
     * @param holder the projected holder of the requested IBAN, empty if there is none
     * @param name the payee name as requested
     * @param organisationId the requested organisation id; {@code null} or blank if none
     */
    public VerificationAnswer verify(Optional<ProjectedHolder> holder, String name, String organisationId) {
        if (holder.isEmpty() || !verifiable(holder.get().holder().status())) {
            return VerificationAnswer.of(VerificationResult.NOT_POSSIBLE);
        }
        AccountHolder account = holder.get().holder();
        String requestedId = organisationId == null ? "" : organisationId.strip();
        if (!requestedId.isEmpty() && account.holderType() == HolderType.ORGANISATION
                && account.organisationId() != null) {
            return VerificationAnswer.of(sameId(account.organisationId(), requestedId)
                    ? VerificationResult.MATCH
                    : VerificationResult.NO_MATCH);
        }
        VerificationResult result = matcher.match(holder.get().normalisedName(), normaliser.normalise(name));
        return result == VerificationResult.CLOSE_MATCH
                ? new VerificationAnswer(result, account.holderName())
                : VerificationAnswer.of(result);
    }

    private static boolean verifiable(AccountStatus status) {
        return status == AccountStatus.OPEN || status == AccountStatus.BLOCKED;
    }

    private static boolean sameId(String stored, String requested) {
        return stored.strip().toUpperCase(Locale.ROOT).equals(requested.toUpperCase(Locale.ROOT));
    }
}
