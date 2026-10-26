package com.tpe.paymentdomain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

final class Rejections {

    private Rejections() {}

    static void assertRejected(ThrowingCallable construction, ValidationCode expected) {
        assertThatThrownBy(construction)
                .isInstanceOf(DomainValidationException.class)
                .extracting(e -> ((DomainValidationException) e).code())
                .isEqualTo(expected);
    }
}
