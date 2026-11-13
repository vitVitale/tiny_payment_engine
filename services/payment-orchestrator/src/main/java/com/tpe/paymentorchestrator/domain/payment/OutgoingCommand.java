package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.RemittanceInformation;
import java.util.Objects;

/**
 * What payment-api asks for in {@code SubmitPayment} (specs/11-payment-orchestrator.md §3.1), already validated.
 *
 * @param creditorBic the creditor's bank as the payer gave it; {@code null}: taken from the bank directory
 * @param remittance may be {@code null}
 */
public record OutgoingCommand(
        CommandId commandId,
        String clientId,
        EndToEndId endToEndId,
        PartyName debtorName,
        Iban debtorIban,
        PartyName creditorName,
        Iban creditorIban,
        Bic creditorBic,
        Money amount,
        RemittanceInformation remittance,
        String vopReference,
        PayeeVerification vopResult) {

    public OutgoingCommand {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(endToEndId, "endToEndId");
        Objects.requireNonNull(debtorName, "debtorName");
        Objects.requireNonNull(debtorIban, "debtorIban");
        Objects.requireNonNull(creditorName, "creditorName");
        Objects.requireNonNull(creditorIban, "creditorIban");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(vopReference, "vopReference");
        Objects.requireNonNull(vopResult, "vopResult");
    }

    /** Leaves out the parties and the remittance text, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "OutgoingCommand[commandId=" + commandId.value() + ", clientId=" + clientId + ", amount=" + amount + "]";
    }
}
