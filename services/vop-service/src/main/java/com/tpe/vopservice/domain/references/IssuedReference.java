package com.tpe.vopservice.domain.references;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.domain.matching.VerificationResult;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stored verification result, a row of {@code vop_results} (specs/13-vop-service.md §5, UC-02 5): what payment-api
 * may later accept the {@code vopReference} for. {@link #toString()} leaves out IBANs and names (26 §4.4).
 *
 * @param payeeName the payee name as requested; {@code null} once retention has cleared it
 * @param actualName for {@code CLOSE_MATCH} the holder name as answered; else, or after retention, {@code null}
 */
public record IssuedReference(
        String vopReference,
        Iban debtorIban,
        Iban payeeIban,
        String payeeName,
        VerificationResult result,
        String actualName,
        Bic respondedBy,
        Instant createdAt,
        Instant expiresAt) {

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    public IssuedReference {
        Objects.requireNonNull(vopReference, "vopReference");
        Objects.requireNonNull(debtorIban, "debtorIban");
        Objects.requireNonNull(payeeIban, "payeeIban");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(respondedBy, "respondedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * Whether a payment from {@code debtorIban} to {@code payeeIban} named {@code payeeName} may quote this reference
     * at {@code now} (13 §3.1, UC-01 4): expired from {@code expiresAt} on; otherwise the IBANs must be equal and
     * the names equal after {@link #canonicalName}, case included (the payer pays exactly the name verified).
     */
    public ReferenceCheck check(Iban debtorIban, Iban payeeIban, String payeeName, Instant now) {
        if (!now.isBefore(expiresAt)) {
            return ReferenceCheck.invalid(ReferenceProblem.EXPIRED);
        }
        boolean same = this.debtorIban.equals(debtorIban) && this.payeeIban.equals(payeeIban)
                && this.payeeName != null && canonicalName(this.payeeName).equals(canonicalName(payeeName));
        return same ? ReferenceCheck.valid(result) : ReferenceCheck.invalid(ReferenceProblem.MISMATCH);
    }

    /** Trimmed, every run of whitespace one space; nothing else changes. */
    public static String canonicalName(String name) {
        return WHITESPACE.matcher(name).replaceAll(" ").strip();
    }

    @Override
    public String toString() {
        return "IssuedReference[vopReference=" + vopReference + ", result=" + result + ", respondedBy="
                + respondedBy.value() + ", createdAt=" + createdAt + ", expiresAt=" + expiresAt + "]";
    }
}
