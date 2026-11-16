package com.tpe.csmgateway.api.admin;

import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Journalled;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer;
import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin API, read-only, role {@code BACKOFFICE_OPERATOR} (specs/15-csm-gateway.md §3.2). A {@code TxId} alone is
 * not an identity (ADR-010): both answers list every match, each with its debtor agent BIC; {@code debtorAgentBic}
 * narrows to one transaction. Incoming transactions are not recorded yet, so a transaction is an outgoing one.
 */
@RestController
@PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
class AdminController {

    /** A journalled message. */
    record JournalView(long id, String direction, String messageType, String msgId, String txId,
            String debtorAgentBic, String xml, boolean valid, List<String> validationErrors, String note, Instant at,
            String transmitStatus) {

        static JournalView of(Journalled journalled) {
            var e = journalled.entry();
            return new JournalView(journalled.id(), e.direction().name(), e.messageType(), e.msgId(),
                    e.txId() == null ? null : e.txId().value(),
                    e.debtorAgentBic() == null ? null : e.debtorAgentBic().value(), e.xml(), e.valid(),
                    e.validationErrors(), e.note(), e.at(), journalled.transmitStatus());
        }
    }

    /** A transaction record. */
    record TransactionView(String direction, String txId, String debtorAgentBic, UUID paymentId, UUID uetr,
            String msgId, Instant acceptedAt, Instant sendBy, Instant deadlineAt, String sendStatus,
            String notSentReason, Instant journalledAt, String transmitStatus, int transmitAttempts,
            Instant deliveredAt, String finalStatus, String finalReason, Instant finalAt) {

        static TransactionView outgoing(OutgoingTransfer t, Bic ownBic) {
            return new TransactionView("OUTGOING", t.txId().value(), ownBic.value(), t.paymentId().value(), t.uetr(),
                    t.msgId(), t.acceptedAt(), t.sendBy(), t.deadlineAt(), t.sendStatus().name(), t.notSentReason(),
                    t.journalledAt(), t.transmitStatus() == null ? null : t.transmitStatus().name(),
                    t.transmitAttempts(), t.deliveredAt(), t.finalStatus() == null ? null : t.finalStatus().name(),
                    t.finalReason(), t.finalAt());
        }
    }

    private final MessageJournal journal;
    private final OutgoingTransfers transfers;
    private final Bic ownBic;

    AdminController(MessageJournal journal, OutgoingTransfers transfers, BankDirectory directory) {
        this.journal = journal;
        this.transfers = transfers;
        this.ownBic = directory.ownBic();
    }

    @GetMapping("/v1/journal")
    List<JournalView> journal(@RequestParam String txId, @RequestParam(required = false) String debtorAgentBic) {
        return journal.byTransaction(new TxId(txId), Optional.ofNullable(debtorAgentBic).map(Bic::new)).stream()
                .map(JournalView::of).toList();
    }

    /** {@code 404 NOT_FOUND} if no transaction has that {@code txId} (and debtor agent). */
    /** Empty if no transaction has that {@code txId} (and debtor agent). */
    @GetMapping("/v1/transactions/{txId}")
    List<TransactionView> transactions(@PathVariable String txId,
            @RequestParam(required = false) String debtorAgentBic) {
        Optional<Bic> bic = Optional.ofNullable(debtorAgentBic).map(Bic::new);
        return transfers.find(new TxId(txId))
                .filter(transfer -> bic.isEmpty() || bic.get().equals(ownBic))
                .map(transfer -> List.of(TransactionView.outgoing(transfer, ownBic)))
                .orElse(List.of());
    }
}
