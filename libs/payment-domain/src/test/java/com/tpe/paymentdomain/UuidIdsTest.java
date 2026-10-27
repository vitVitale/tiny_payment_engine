package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_CHARACTERS;
import static com.tpe.paymentdomain.ValidationCode.INVALID_UETR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UuidIdsTest {

    @Test
    void uetrAcceptsLowerCaseUuidV4() {
        assertThat(new Uetr("eb6305c9-1f7f-49de-aed0-16487c27b42d").value())
                .isEqualTo("eb6305c9-1f7f-49de-aed0-16487c27b42d");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "EB6305C9-1F7F-49DE-AED0-16487C27B42D", // upper case
        "eb6305c9-1f7f-19de-aed0-16487c27b42d", // version 1
        "eb6305c9-1f7f-79de-aed0-16487c27b42d", // version 7
        "eb6305c9-1f7f-49de-ced0-16487c27b42d", // variant not RFC 4122
        "eb6305c91f7f49deaed016487c27b42d", // no hyphens
        "eb6305c9-1f7f-49de-aed0-16487c27b42", // too short
        "eb6305c9-1f7f-49de-aed0-16487c27b42dd", // too long
        "",
    })
    void uetrRejectsAnythingButLowerCaseUuidV4(String uetr) {
        assertRejected(() -> new Uetr(uetr), INVALID_UETR);
    }

    @Test
    void paymentIdWrapsUuid() {
        UUID uuid = UUID.fromString("01928c6e-7b3a-7cde-8f00-123456789abc");

        assertThat(new PaymentId(uuid).value()).isEqualTo(uuid);
        assertThat(new PaymentId(uuid)).isEqualTo(new PaymentId(uuid));
        assertThatThrownBy(() -> new PaymentId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void commandNamespaceIsThePinnedConstant() {
        assertThat(CommandId.TPE_COMMAND_NS).isEqualTo(UUID.fromString("481802bd-0ea1-46e6-81c2-b39dc5132613"));
    }

    // Expected values computed independently with Python: uuid.uuid5(TPE_COMMAND_NS, clientId + "\n" + key)
    @ParameterizedTest
    @CsvSource({
        "client-a,   key-123,                              667658c9-85b4-509f-922b-229fbff65e4a",
        "mobile-app, 8f14e45f-ceea-467f-a0e6-1f1b2c3d4e5f, 4a1fe8ba-51a8-5c70-a261-f69fa490c5a4",
        "client-a,   key-124,                              8fadb37d-af6d-5bf8-9a66-e8b76ca3e205",
    })
    void deriveIsUuidV5OfClientIdAndIdempotencyKey(String clientId, String key, String expected) {
        CommandId derived = CommandId.derive(clientId, key);

        assertThat(derived.value()).isEqualTo(UUID.fromString(expected));
        assertThat(derived.value().version()).isEqualTo(5);
        assertThat(derived.value().variant()).isEqualTo(2);
    }

    @Test
    void deriveIsDeterministic() {
        assertThat(CommandId.derive("client-a", "key-123")).isEqualTo(CommandId.derive("client-a", "key-123"));
    }

    @Test
    void deriveRejectsClientIdWithNewlineSoNamesCannotCollide() {
        // "a\nb" + "\n" + "c" would equal "a" + "\n" + "b\nc"
        assertRejected(() -> CommandId.derive("a\nb", "c"), INVALID_CHARACTERS);
    }
}
