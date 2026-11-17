package com.tpe.paymentorchestrator.application.cases;

import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import java.util.UUID;

/**
 * The case's payment moved on since the case opened, e.g. the CSM answered the investigation after all:
 * {@code 409 CASE_OUTDATED}.
 */
public class CaseOutdatedException extends RuntimeException {

    CaseOutdatedException(UUID caseId, PaymentState state) {
        super("the payment of case " + caseId + " is " + state + " now");
    }
}
