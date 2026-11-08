package com.tpe.invariantchecker;

/**
 * One breach of a global invariant (specs/20-testing-strategy.md §3): the invariant's id ({@code INV-1}, …), the row it
 * was found on (a journal entry or account id) and what is wrong, for the test report or the alert.
 */
public record Violation(String invariant, String subject, String detail) {

    @Override
    public String toString() {
        return invariant + " " + subject + ": " + detail;
    }
}
