package com.tpe.systemtests.harness;

/**
 * The payment-api scopes a customer-channel token carries (specs/00-engineering-baseline.md §12.1).
 * {@code notifications:manage}, the subscription API's scope, joins with notification-service.
 */
public enum CustomerScope {
    PAYMENTS_WRITE("payments:write"),
    PAYMENTS_READ("payments:read"),
    VOP_VERIFY("vop:verify");

    private final String value;

    CustomerScope(String value) {
        this.value = value;
    }

    /** The scope as it appears in the token's space-separated {@code scope} claim. */
    public String value() {
        return value;
    }
}
