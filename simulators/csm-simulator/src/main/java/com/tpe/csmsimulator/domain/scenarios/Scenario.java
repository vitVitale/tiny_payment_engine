package com.tpe.csmsimulator.domain.scenarios;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What the simulated beneficiary PSP does with a payment to a creditor (specs/17-csm-simulator.md §3.2, §3.5), written
 * as the control API takes it: {@code ACCEPT}, {@code REJECT:<code>}, {@code TIMEOUT}, {@code DELAY:<ms>}.
 */
public sealed interface Scenario {

    /** The scenario as the control API writes it. */
    String text();

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
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay is negative");
            }
        }

        @Override
        public String text() {
            return "DELAY:" + delay.toMillis();
        }
    }

    /** An ISO external status reason code: {@code Max4Text}, as the codes are written. */
    Pattern REASON_CODE = Pattern.compile("[A-Z0-9]{4}");
    Pattern MILLIS = Pattern.compile("[0-9]{1,9}");

    /** @throws IllegalArgumentException if {@code text} is not a scenario this simulator plays */
    static Scenario parse(String text) {
        Objects.requireNonNull(text, "text");
        int colon = text.indexOf(':');
        String name = colon < 0 ? text : text.substring(0, colon);
        String argument = colon < 0 ? null : text.substring(colon + 1);
        return switch (name) {
            case "ACCEPT" -> noArgument(text, argument, new Accept());
            case "TIMEOUT" -> noArgument(text, argument, new Timeout());
            case "REJECT" -> {
                if (argument == null || !REASON_CODE.matcher(argument).matches()) {
                    throw new IllegalArgumentException("REJECT needs a four-character reason code: " + text);
                }
                yield new Reject(ReasonCode.parse(argument));
            }
            case "DELAY" -> {
                if (argument == null || !MILLIS.matcher(argument).matches()) {
                    throw new IllegalArgumentException("DELAY needs milliseconds: " + text);
                }
                yield new Delay(Duration.ofMillis(Long.parseLong(argument)));
            }
            default -> throw new IllegalArgumentException("not a scenario: " + text);
        };
    }

    private static Scenario noArgument(String text, String argument, Scenario scenario) {
        if (argument != null) {
            throw new IllegalArgumentException("takes no argument: " + text);
        }
        return scenario;
    }
}
