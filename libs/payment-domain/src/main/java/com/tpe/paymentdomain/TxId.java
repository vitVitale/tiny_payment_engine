package com.tpe.paymentdomain;

/**
 * Transaction identifier assigned by the Originator PSP: ISO {@code Max35Text}, SEPA character set. Unique only
 * per Originator PSP (ADR-010).
 */
public record TxId(String value) {

    public TxId {
        SepaCharacterSet.requireText(value, 35);
    }

    /** An outgoing TxId: {@code TPE} + ULID, 29 characters (specs/00-engineering-baseline.md §4). */
    public static TxId generate(UlidGenerator ulids) {
        return new TxId("TPE" + ulids.next());
    }
}
