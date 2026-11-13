package com.tpe.paymentorchestrator.api.command;

import com.tpe.contracts.Direction;
import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentorchestrator.api.FieldError;
import com.tpe.paymentorchestrator.api.InternalApi;
import com.tpe.paymentorchestrator.api.ValidationFailedException;
import com.tpe.paymentorchestrator.application.outgoing.PaymentSubmission;
import com.tpe.paymentorchestrator.application.payments.PaymentReader;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCommand;
import com.tpe.paymentorchestrator.domain.payment.PayeeVerification;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment command API, called by payment-api (specs/11-payment-orchestrator.md §3.1). Request and response
 * records are in {@code contracts}. Unauthenticated until M5 (ADR-012).
 */
@InternalApi
@RestController
class PaymentCommandController {

    static final String PAYMENTS = "/v1/payments";

    private final PaymentSubmission submission;
    private final PaymentReader reader;
    private final RequestFingerprint fingerprint;

    PaymentCommandController(PaymentSubmission submission, PaymentReader reader, RequestFingerprint fingerprint) {
        this.submission = submission;
        this.reader = reader;
        this.fingerprint = fingerprint;
    }

    /**
     * {@code 201} with a new payment; {@code 200} with the payment of an earlier submit of the same request;
     * {@code 409 COMMAND_CONFLICT} for the same {@code commandId} with another request; {@code 422
     * CREDITOR_AGENT_NOT_REACHABLE}; {@code 400 VALIDATION_FAILED} for a missing field.
     */
    @PostMapping(PAYMENTS)
    ResponseEntity<PaymentAccepted> submit(@RequestBody SubmitPayment request) {
        OutgoingCommand command = command(request);
        PaymentSubmission.Submitted submitted = submission.submit(command, fingerprint.of(PAYMENTS, request));
        Payment payment = submitted.payment();
        return ResponseEntity.status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new PaymentAccepted(payment.paymentId(), PaymentState.valueOf(payment.state().name()),
                        payment.txId(), payment.acceptedAt()));
    }

    /** {@code 404 PAYMENT_NOT_FOUND} for an unknown id. */
    @GetMapping(PAYMENTS + "/{paymentId}")
    PaymentView get(@PathVariable UUID paymentId) {
        Payment payment = reader.get(new PaymentId(paymentId));
        return new PaymentView(payment.paymentId(), Direction.valueOf(payment.direction().name()), payment.onUs(),
                PaymentState.valueOf(payment.state().name()), payment.reasonCode(), payment.txId(), payment.uetr(),
                payment.endToEndId(), payment.amount(), holder(payment.debtor()), holder(payment.creditor()),
                payment.remittance(), payment.acceptedAt(), payment.finalisedAt());
    }

    private static PaymentView.Holder holder(PaymentParty party) {
        return new PaymentView.Holder(party.name(), party.iban());
    }

    private static OutgoingCommand command(SubmitPayment request) {
        List<FieldError> errors = new ArrayList<>();
        required("commandId", request.commandId(), errors);
        requiredText("clientId", request.clientId(), errors);
        required("endToEndId", request.endToEndId(), errors);
        if (required("debtor", request.debtor(), errors)) {
            required("debtor.name", request.debtor().name(), errors);
            required("debtor.iban", request.debtor().iban(), errors);
        }
        if (required("creditor", request.creditor(), errors)) {
            required("creditor.name", request.creditor().name(), errors);
            required("creditor.iban", request.creditor().iban(), errors);
        }
        required("amountMinor", request.amount(), errors);
        requiredText("vopReference", request.vopReference(), errors);
        required("vopResult", request.vopResult(), errors);
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return new OutgoingCommand(new CommandId(request.commandId()), request.clientId(), request.endToEndId(),
                request.debtor().name(), request.debtor().iban(), request.creditor().name(),
                request.creditor().iban(), request.creditor().bic(), request.amount(),
                request.remittanceInformation(), request.vopReference(),
                PayeeVerification.valueOf(request.vopResult().name()));
    }

    /** @return whether the value is there */
    private static boolean required(String field, Object value, List<FieldError> errors) {
        if (value == null) {
            errors.add(new FieldError(field, "REQUIRED"));
            return false;
        }
        return true;
    }

    private static void requiredText(String field, String value, List<FieldError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldError(field, "REQUIRED"));
        }
    }
}
