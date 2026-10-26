package com.tpe.traceabilitycheck;

/** One finding of the traceability check. */
record Violation(String id, Reason reason) implements Comparable<Violation> {

    enum Reason {
        NO_TEST("no test carries this id and it is not in the pending-ids baseline"),
        NO_CONSUMER_PACT_TEST("no consumer pact test (@PactTestFor) carries this id and it is not in the pending-ids baseline"),
        NO_PROVIDER_VERIFICATION("no provider verification (@Provider) carries this id and it is not in the pending-ids baseline"),
        PENDING_BUT_TESTED("a test now carries this id; remove it from the pending-ids baseline"),
        PENDING_BUT_NOT_IN_SPECS("not in the specs; remove it from the pending-ids baseline"),
        ADDED_BUT_ON_MAIN("already in the specs on main, so it may not be added to the pending-ids baseline");

        private final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    String message() {
        return id + ": " + reason.text;
    }

    @Override
    public int compareTo(Violation other) {
        int byId = id.compareTo(other.id);
        return byId != 0 ? byId : reason.compareTo(other.reason);
    }
}
