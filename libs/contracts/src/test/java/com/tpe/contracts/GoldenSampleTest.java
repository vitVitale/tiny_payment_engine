package com.tpe.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The golden-sample harness of specs/01-shared-libraries.md §4: for every message type, (a) the current record
 * serialises to {@code <Type>.v<current>.json}, (b) every sample of an older or the current version still
 * deserialises, (c) a sample with an extra unknown field deserialises. Add a message to {@link #messages()}.
 */
class GoldenSampleTest {

    private static final Pattern SAMPLE = Pattern.compile("(\\w+)\\.v(\\d+)\\.json");

    private final ContractJson json = new ContractJson();
    private final JsonMapper plain = JsonMapper.builder().build();

    static Stream<Message> messages() {
        return Stream.of(
                Samples.sendCreditTransfer(),
                Samples.requestPaymentStatus(),
                Samples.acceptCreditTransfer(),
                Samples.rejectCreditTransfer());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void currentRecordSerialisesToItsGoldenSample(Message message) {
        JsonNode written = plain.readTree(json.write(message));

        assertThat(written).isEqualTo(plain.readTree(golden(message.type(), message.schemaVersion())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void everySampleUpToTheCurrentVersionDeserialises(Message message) {
        List<Integer> versions = versionsOf(message.type());

        assertThat(versions).contains(message.schemaVersion());
        for (int version : versions) {
            assertThat(version).isLessThanOrEqualTo(message.schemaVersion());
            Message read = json.read(golden(message.type(), version), message.getClass());
            if (version == message.schemaVersion()) {
                assertThat(read).isEqualTo(message);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void sampleWithAnExtraUnknownFieldDeserialises(Message message) {
        ObjectNode sample = (ObjectNode) plain.readTree(golden(message.type(), message.schemaVersion()));
        sample.put("addedInAFutureVersion", "ignored");
        sample.putObject("nestedFutureField").put("x", 1);

        assertThat(json.read(plain.writeValueAsString(sample), message.getClass())).isEqualTo(message);
    }

    @Test
    void everyMessageTypeIsCoveredByTheHarness() {
        List<Class<?>> covered = messages().<Class<?>>map(Message::getClass).toList();

        assertThat(covered).containsExactlyInAnyOrderElementsOf(Arrays.asList(Message.class.getPermittedSubclasses()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void recordHasNoPrimitiveComponents(Message message) {
        // a missing primitive reads as its default and escapes the required-field check (OptionalField)
        assertThat(message.getClass().getRecordComponents())
                .noneMatch(component -> component.getType().isPrimitive());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void typeIsTheRecordName(Message message) {
        assertThat(message.type()).isEqualTo(message.getClass().getSimpleName());
        assertThat(message.schemaVersion()).isPositive();
    }

    private static String golden(String type, int version) {
        try {
            return Files.readString(goldenDirectory().resolve(type + ".v" + version + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Integer> versionsOf(String type) {
        try (Stream<Path> files = Files.list(goldenDirectory())) {
            return files.map(file -> SAMPLE.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .filter(name -> name.group(1).equals(type))
                    .map(name -> Integer.parseInt(name.group(2)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path goldenDirectory() {
        try {
            return Path.of(GoldenSampleTest.class.getResource("/golden").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
