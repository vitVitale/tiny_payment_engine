package com.tpe.paymentapi.application.verification;

import com.tpe.contracts.vop.VerificationRequest;
import com.tpe.contracts.vop.VerificationResponse;
import com.tpe.paymentapi.application.submission.Failure;

/** vop-service's {@code POST /v1/verifications} (specs/13-vop-service.md §3.1, 10 §3.4). */
public interface PayeeVerifications {

    /** One attempt; it is not retried (10 §4). */
    Answer verify(VerificationRequest request);

    /** What vop-service answered, or that it could not be asked. */
    sealed interface Answer {}

    /** {@code 201}: the verification and its reference. */
    record Verified(VerificationResponse response) implements Answer {}

    /** {@code 422 PAYEE_BANK_UNKNOWN}: the payee IBAN's bank code is not in the directory. */
    record PayeeBankUnknown() implements Answer {}

    /** Not reached, no answer in time, or a {@code 5xx}. */
    record Unavailable(Failure failure, String why) implements Answer {}
}
