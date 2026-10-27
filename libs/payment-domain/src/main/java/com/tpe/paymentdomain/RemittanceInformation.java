package com.tpe.paymentdomain;

/**
 * Unstructured remittance information: at most 140 characters, SEPA character set. It is personal data and never
 * logged, so {@link #toString()} hides the value (specs/00-engineering-baseline.md §11).
 */
public record RemittanceInformation(String value) {

    public RemittanceInformation {
        SepaCharacterSet.requireText(value, 140);
    }

    @Override
    public String toString() {
        return "RemittanceInformation[***]";
    }
}
