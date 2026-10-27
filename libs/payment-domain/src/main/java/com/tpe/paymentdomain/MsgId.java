package com.tpe.paymentdomain;

/** ISO message identifier: {@code Max35Text}, SEPA character set. */
public record MsgId(String value) {

    public MsgId {
        SepaCharacterSet.requireText(value, 35);
    }

    /** An outgoing MsgId: {@code TPEM} + ULID, 30 characters (specs/00-engineering-baseline.md §4). */
    public static MsgId generate(UlidGenerator ulids) {
        return new MsgId("TPEM" + ulids.next());
    }
}
