package com.tpe.paymentapi.application.submission;

import com.tpe.contracts.vop.VopReferenceProblem;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;

/** vop-service's {@code POST /v1/verifications/{vopReference}/check} (specs/13-vop-service.md §3.1, 10 §5.3). */
public interface ReferenceChecks {

    /** One attempt; retries are the caller's (specs/00-engineering-baseline.md §8). */
    Answer check(String vopReference, Iban debtorIban, Iban payeeIban, PartyName payeeName);

    /** What vop-service answered, or that it could not be asked. */
    sealed interface Answer {}

    /** The reference covers the payment; {@code result} is the verification's, passed on as {@code vopResult}. */
    record Valid(VopResult result) implements Answer {}

    /** The reference does not cover the payment. */
    record Invalid(VopReferenceProblem problem) implements Answer {}

    /** Not reached, no answer in time, or a {@code 5xx}. */
    record Unavailable(Failure failure, String why) implements Answer {}
}
