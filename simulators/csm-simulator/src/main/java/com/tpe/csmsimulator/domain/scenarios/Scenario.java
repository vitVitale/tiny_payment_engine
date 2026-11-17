package com.tpe.csmsimulator.domain.scenarios;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What the simulated CSM and beneficiary PSP do with a payment to a creditor (specs/17-csm-simulator.md §3.2, §3.5),
 * written as the control API takes it: {@code ACCEPT}, {@code REJECT:<code>}, {@code TIMEOUT}, {@code DELAY:<ms>},
 * {@code SILENT}, {@code LOST}, {@code LATE_STATUS:<ms>}, and {@code INVESTIGATION_SILENT}, alone (the payment is
 * {@code ACCEPT}) or joined to one of the others with {@code +}.
 */
public sealed interface Scenario {

    /** The scenario as the control API writes it. */
    String text();

    /** What happens to the payment itself. */
    default Scenario payment() {
        return this;
    }

    /** Whether the CSM ignores a {@code pacs.028} about the payment. */
    default boolean ignoresInvestigations() {
        return false;
    }

    /** The beneficiary accepts after the configured beneficiary delay. */
    record Accept() implements Scenario {

        @Override
        public String text() {
            return "ACCEPT";
        }
    }

    /** The beneficiary rejects at once with {@code reason}. */
    record Reject(StatusReason reason) implements Scenario {

        public Reject {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String text() {
            return "REJECT:" + reason.code();
        }
    }

    /** The beneficiary never answers; the CSM rejects with {@code AB05} at the time-out deadline. */
    record Timeout() implements Scenario {

        @Override
        public String text() {
            return "TIMEOUT";
        }
    }

    /** The beneficiary accepts after {@code delay} instead of the beneficiary delay. */
    record Delay(Duration delay) implements Scenario {

        public Delay {
            requireNotNegative(delay, "delay");
        }

        @Override
        public String text() {
            return "DELAY:" + delay.toMillis();
        }
    }

    /** As {@link Accept}, but the CSM sends no {@code pacs.002}. */
    record Silent() implements Scenario {

        @Override
        public String text() {
            return "SILENT";
        }
    }

    /** The CSM takes the transport ({@code 202}) and drops the message: it never received the transaction. */
    record Lost() implements Scenario {

        @Override
        public String text() {
            return "LOST";
        }
    }

    /** As {@link Accept}, but the {@code pacs.002} goes only at {@code AccptncDtTm + delay}. */
    record LateStatus(Duration delay) implements Scenario {

        public LateStatus {
            requireNotNegative(delay, "delay");
        }

        @Override
        public String text() {
            return "LATE_STATUS:" + delay.toMillis();
        }
    }

    /** The {@code payment} scenario, and every {@code pacs.028} about the payment is ignored. */
    record InvestigationSilent(Scenario payment) implements Scenario {

        public InvestigationSilent {
            Objects.requireNonNull(payment, "payment");
            if (payment instanceof InvestigationSilent) {
                throw new IllegalArgumentException("INVESTIGATION_SILENT twice");
            }
        }

        @Override
        public String text() {
            return payment instanceof Accept ? INVESTIGATION_SILENT
                    : payment.text() + "+" + INVESTIGATION_SILENT;
        }

        @Override
        public boolean ignoresInvestigations() {
            return true;
        }
    }

    String INVESTIGATION_SILENT = "INVESTIGATION_SILENT";

    /** An ISO external status reason code: {@code Max4Text}, as the codes are written. */
    Pattern REASON_CODE = Pattern.compile("[A-Z0-9]{4}");
    Pattern MILLIS = Pattern.compile("[0-9]{1,9}");

    /** @throws IllegalArgumentException if {@code text} is not a scenario this simulator plays */
    static Scenario parse(String text) {
        Objects.requireNonNull(text, "text");
        int plus = text.indexOf('+');
        if (plus < 0) {
            return INVESTIGATION_SILENT.equals(text) ? new InvestigationSilent(new Accept()) : payment(text);
        }
        String first = text.substring(0, plus);
        String second = text.substring(plus + 1);
        if (INVESTIGATION_SILENT.equals(second) && !first.equals(second)) {
            return new InvestigationSilent(payment(first));
        }
        if (INVESTIGATION_SILENT.equals(first) && !first.equals(second)) {
            return new InvestigationSilent(payment(second));
        }
        throw new IllegalArgumentException("only INVESTIGATION_SILENT joins another scenario: " + text);
    }

    private static Scenario payment(String text) {
        int colon = text.indexOf(':');
        String name = colon < 0 ? text : text.substring(0, colon);
        String argument = colon < 0 ? null : text.substring(colon + 1);
        return switch (name) {
            case "ACCEPT" -> noArgument(text, argument, new Accept());
            case "TIMEOUT" -> noArgument(text, argument, new Timeout());
            case "SILENT" -> noArgument(text, argument, new Silent());
            case "LOST" -> noArgument(text, argument, new Lost());
            case "REJECT" -> {
                if (argument == null || !REASON_CODE.matcher(argument).matches()) {
                    throw new IllegalArgumentException("REJECT needs a four-character reason code: " + text);
                }
                yield new Reject(ReasonCode.parse(argument));
            }
            case "DELAY" -> new Delay(millis(text, argument));
            case "LATE_STATUS" -> new LateStatus(millis(text, argument));
            default -> throw new IllegalArgumentException("not a scenario: " + text);
        };
    }

    private static Duration millis(String text, String argument) {
        if (argument == null || !MILLIS.matcher(argument).matches()) {
            throw new IllegalArgumentException("needs milliseconds: " + text);
        }
        return Duration.ofMillis(Long.parseLong(argument));
    }

    private static Scenario noArgument(String text, String argument, Scenario scenario) {
        if (argument != null) {
            throw new IllegalArgumentException("takes no argument: " + text);
        }
        return scenario;
    }

    private static void requireNotNegative(Duration delay, String name) {
        Objects.requireNonNull(delay, name);
        if (delay.isNegative()) {
            throw new IllegalArgumentException(name + " is negative");
        }
    }
}
