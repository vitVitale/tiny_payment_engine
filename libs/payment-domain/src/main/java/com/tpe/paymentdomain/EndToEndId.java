package com.tpe.paymentdomain;

/** The payer's end-to-end reference: ISO {@code Max35Text}, SEPA character set, or {@link #NOT_PROVIDED}. */
public record EndToEndId(String value) {

    public static final EndToEndId NOT_PROVIDED = new EndToEndId("NOTPROVIDED");

    public EndToEndId {
        SepaCharacterSet.requireText(value, 35);
    }

    public boolean isProvided() {
        return !equals(NOT_PROVIDED);
    }
}
