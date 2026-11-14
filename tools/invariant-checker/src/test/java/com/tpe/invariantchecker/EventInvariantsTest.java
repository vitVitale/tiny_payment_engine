package com.tpe.invariantchecker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tpe.invariantchecker.EventInvariants.DeadLetter;
import com.tpe.invariantchecker.EventInvariants.FinalEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** INV-7 and the DLT part of INV-9 (specs/20-testing-strategy.md §3), each with its negative test. */
class EventInvariantsTest {

    private final UUID settled = UUID.randomUUID();
    private final UUID rejected = UUID.randomUUID();
    private final UUID pending = UUID.randomUUID();
    private final Map<UUID, String> states = Map.of(settled, "SETTLED", rejected, "REJECTED", pending, "SENT_TO_CSM");

    @Test
    void oneEventOfItsOutcomePerFinalPaymentAndNoneOtherwisePasses() {
        var events = List.of(new FinalEvent(settled, "PaymentSettled"), new FinalEvent(rejected, "PaymentRejected"),
                new FinalEvent(UUID.randomUUID(), "PaymentSettled"));

        assertThat(EventInvariants.finalEvents(states, events)).isEmpty();
    }

    @Test
    void INV_7_aSecondFinalEventFails() {
        var events = List.of(new FinalEvent(settled, "PaymentSettled"), new FinalEvent(settled, "PaymentSettled"),
                new FinalEvent(rejected, "PaymentRejected"));

        assertThat(EventInvariants.finalEvents(states, events)).extracting(Violation::invariant, Violation::subject)
                .containsExactly(tuple("INV-7", settled.toString()));
    }

    @Test
    void INV_7_aMissingFinalEventFails() {
        var events = List.of(new FinalEvent(settled, "PaymentSettled"));

        assertThat(EventInvariants.finalEvents(states, events)).extracting(Violation::subject)
                .containsExactly(rejected.toString());
    }

    @Test
    void INV_7_aFinalEventOfTheOtherOutcomeFails() {
        var events = List.of(new FinalEvent(settled, "PaymentRejected"), new FinalEvent(rejected, "PaymentRejected"));

        assertThat(EventInvariants.finalEvents(states, events)).extracting(Violation::subject)
                .containsExactly(settled.toString());
    }

    @Test
    void INV_7_aFinalEventOfAPaymentThatIsNotFinalFails() {
        var events = List.of(new FinalEvent(settled, "PaymentSettled"), new FinalEvent(rejected, "PaymentRejected"),
                new FinalEvent(pending, "PaymentSettled"));

        assertThat(EventInvariants.finalEvents(states, events)).extracting(Violation::subject)
                .containsExactly(pending.toString());
    }

    @Test
    void INV_9_aDeadLetterFails() {
        assertThat(EventInvariants.noDeadLetters(List.of())).isEmpty();
        assertThat(EventInvariants.noDeadLetters(List.of(new DeadLetter("accounts.events.dlt", "k1", "poison"))))
                .extracting(Violation::invariant, Violation::subject)
                .containsExactly(tuple("INV-9", "accounts.events.dlt/k1"));
    }
}
