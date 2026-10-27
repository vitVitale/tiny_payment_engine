package com.tpe.paymentdomain;

/** An ISO status reason: one of the {@link ReasonCode}s this design uses, or any other code received from the CSM. */
public sealed interface StatusReason permits ReasonCode, ReasonCode.Other {

    /** The ISO {@code ExternalStatusReason1Code}, e.g. {@code AM04}. */
    String code();
}
