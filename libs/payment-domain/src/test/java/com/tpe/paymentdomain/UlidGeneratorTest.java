package com.tpe.paymentdomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class UlidGeneratorTest {

    private static final String CROCKFORD = "[0-9A-HJKMNP-TV-Z]";

    /** A random source that always returns the same value. */
    private static RandomGenerator constant(long value) {
        return () -> value;
    }

    @Test
    void isTwentySixCrockfordCharacters() {
        UlidGenerator generator = new UlidGenerator(new MutableClock(Instant.parse("2026-10-21T09:15:30Z")), new SplittableRandom(1));

        assertThat(generator.next()).matches(CROCKFORD + "{26}");
    }

    @Test
    void encodesTheMillisecondTimestampInTheFirstTenCharacters() {
        // example of the ULID specification
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(1469918176385L));

        assertThat(new UlidGenerator(clock, new SplittableRandom(1)).next()).startsWith("01ARYZ6S41");
    }

    @Test
    void encodesEightyRandomBitsInTheLastSixteenCharacters() {
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));

        assertThat(new UlidGenerator(clock, constant(0)).next()).isEqualTo("00000000000000000000000000");
        assertThat(new UlidGenerator(clock, constant(-1)).next()).isEqualTo("0000000000ZZZZZZZZZZZZZZZZ");
        // high 16 bits = 1 (bit 64 is the low bit of the 4th random character), low 64 bits = 1
        assertThat(new UlidGenerator(clock, constant(1)).next()).isEqualTo("0000000000000G000000000001");
    }

    @Test
    void incrementsTheRandomPartWithinTheSameMillisecond() {
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
        UlidGenerator generator = new UlidGenerator(clock, constant(0));

        assertThat(generator.next()).isEqualTo("00000000000000000000000000");
        assertThat(generator.next()).isEqualTo("00000000000000000000000001");
        assertThat(generator.next()).isEqualTo("00000000000000000000000002");
    }

    @Test
    void carriesTheIncrementAcrossTheLow64Bits() {
        MutableClock clock = new MutableClock(Instant.ofEpochMilli(0));
        // high 16 bits 0x0000, low 64 bits all ones
        RandomGenerator random = new RandomGenerator() {
            private int calls;

            @Override
            public long nextLong() {
                return calls++ == 0 ? 0 : -1;
            }
        };
        UlidGenerator generator = new UlidGenerator(clock, random);

        assertThat(generator.next()).isEqualTo("0000000000000FZZZZZZZZZZZZ");
        assertThat(generator.next()).isEqualTo("0000000000000G000000000000");
    }

    @Test
    void refusesToOverflowWithinOneMillisecond() {
        UlidGenerator generator = new UlidGenerator(new MutableClock(Instant.ofEpochMilli(0)), constant(-1));
        generator.next();

        assertThatThrownBy(generator::next).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameMillisecondValuesAreStrictlyIncreasing() {
        UlidGenerator generator = new UlidGenerator(new MutableClock(Instant.parse("2026-10-21T09:15:30Z")), new SplittableRandom(7));
        List<String> ulids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ulids.add(generator.next());
        }

        assertThat(ulids).isSortedAccordingTo(String::compareTo).doesNotHaveDuplicates();
    }

    @Property
    void staysStrictlyIncreasingWhateverTheClockDoes(@ForAll @Size(max = 200) List<@IntRange(min = -3, max = 3) Integer> stepsMillis) {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-21T09:15:30Z"));
        UlidGenerator generator = new UlidGenerator(clock, new SplittableRandom(3));
        String previous = generator.next();
        for (int step : stepsMillis) {
            clock.advance(Duration.ofMillis(step));
            String next = generator.next();

            assertThat(next).isGreaterThan(previous);
            previous = next;
        }
    }

    @Test
    void isReproducibleWithAFixedClockAndRandomSource() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-21T09:15:30Z"));
        UlidGenerator first = new UlidGenerator(clock, new SplittableRandom(42));
        UlidGenerator second = new UlidGenerator(clock, new SplittableRandom(42));

        assertThat(List.of(first.next(), first.next())).isEqualTo(List.of(second.next(), second.next()));
    }

    @Test
    void outgoingTxIdIsTpePlusUlid() {
        UlidGenerator generator = new UlidGenerator(new MutableClock(Instant.parse("2026-10-21T09:15:30Z")), new SplittableRandom(1));

        TxId txId = TxId.generate(generator);

        assertThat(txId.value()).hasSize(29).matches("TPE" + CROCKFORD + "{26}").matches("[A-Z0-9]+");
    }

    @Test
    void outgoingMsgIdIsTpemPlusUlid() {
        UlidGenerator generator = new UlidGenerator(new MutableClock(Instant.parse("2026-10-21T09:15:30Z")), new SplittableRandom(1));

        MsgId msgId = MsgId.generate(generator);

        assertThat(msgId.value()).hasSize(30).matches("TPEM" + CROCKFORD + "{26}").matches("[A-Z0-9]+");
    }
}
