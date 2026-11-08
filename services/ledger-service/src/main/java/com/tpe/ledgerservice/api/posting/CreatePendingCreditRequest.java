package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import com.tpe.ledgerservice.application.reservation.CreatePendingCredit;
import com.tpe.ledgerservice.domain.reservation.IncomingReference;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /v1/pending-credits} (specs/12-ledger-service.md §3.1). The id is the incoming payment's
 * {@code paymentId} (ADR-010). A currency other than EUR passes here and is refused by the ledger with
 * {@code 422 CURRENCY_NOT_SUPPORTED}. The reference is kept as received; a {@code debtorAgentBic} must be a BIC, and a
 * {@code paymentId} left out is the {@code pendingCreditId}.
 */
record CreatePendingCreditRequest(UUID pendingCreditId, String creditorIban, Long amountMinor, String currency,
        Reference reference) {

    record Reference(UUID paymentId, String txId, String debtorAgentBic) {}

    /** @throws ValidationFailedException with one error per invalid field */
    CreatePendingCredit toCommand() {
        List<FieldError> errors = new ArrayList<>();
        if (pendingCreditId == null) {
            errors.add(new FieldError("pendingCreditId", "REQUIRED"));
        }
        Iban iban = null;
        if (creditorIban == null || creditorIban.isBlank()) {
            errors.add(new FieldError("creditorIban", "REQUIRED"));
        } else {
            try {
                iban = new Iban(creditorIban);
            } catch (DomainValidationException e) {
                errors.add(new FieldError("creditorIban", e.code().name()));
            }
        }
        if (amountMinor == null) {
            errors.add(new FieldError("amountMinor", "REQUIRED"));
        } else if (amountMinor <= 0) {
            errors.add(new FieldError("amountMinor", "INVALID_AMOUNT"));
        }
        if (currency == null || currency.isBlank()) {
            errors.add(new FieldError("currency", "REQUIRED"));
        }
        Reference ref = reference == null ? new Reference(null, null, null) : reference;
        // the id is the incoming paymentId (ADR-010): a reference may leave it out, never name another one
        UUID paymentId = ref.paymentId() == null ? pendingCreditId : ref.paymentId();
        if (pendingCreditId != null && !pendingCreditId.equals(paymentId)) {
            errors.add(new FieldError("reference.paymentId", "NOT_PENDING_CREDIT_ID"));
        }
        if (ref.txId() != null && ref.txId().length() > CreateHoldRequest.MAX_TX_ID) {
            errors.add(new FieldError("reference.txId", "TOO_LONG"));
        }
        if (ref.debtorAgentBic() != null) {
            try {
                new Bic(ref.debtorAgentBic());
            } catch (DomainValidationException e) {
                errors.add(new FieldError("reference.debtorAgentBic", e.code().name()));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return new CreatePendingCredit(pendingCreditId, iban, amountMinor, currency,
                new IncomingReference(paymentId, ref.txId(), ref.debtorAgentBic()));
    }
}
