package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Jackson serialisers for the {@code payment-domain} value objects in contract records (specs/01-shared-libraries.md
 * §4, 02 §1): {@link Money} as {@code amountMinor} + {@code currency} (unwrapped into the record), single-value types
 * as their string, instants as ISO 8601 UTC with milliseconds, status reasons as their code. A value object that
 * rejects its input fails the read with the {@code DomainValidationException} as cause.
 */
public final class ContractsModule extends SimpleModule {

    /** Always three fraction digits and {@code Z}: {@code 2026-10-21T09:15:30.000Z}. */
    private static final DateTimeFormatter INSTANT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public ContractsModule() {
        super("tpe-contracts");
        setMixInAnnotation(Money.class, MoneyMixin.class);
        registerAsString(Iban.class, Iban::new, Iban::value);
        registerAsString(Bic.class, Bic::new, Bic::value);
        registerAsString(TxId.class, TxId::new, TxId::value);
        registerAsString(Uetr.class, Uetr::new, Uetr::value);
        registerAsString(EndToEndId.class, EndToEndId::new, EndToEndId::value);
        registerAsString(PartyName.class, PartyName::new, PartyName::value);
        registerAsString(RemittanceInformation.class, RemittanceInformation::new, RemittanceInformation::value);
        registerAsString(PaymentId.class, text -> new PaymentId(UUID.fromString(text)), id -> id.value().toString());
        registerAsString(StatusReason.class, ReasonCode::parse, StatusReason::code);
        // a field declared as StatusReason holds one of these; serialisers are looked up by the runtime class
        addSerializer(ReasonCode.class, serializer(ReasonCode.class, ReasonCode::code));
        addSerializer(ReasonCode.Other.class, serializer(ReasonCode.Other.class, ReasonCode.Other::code));
        registerAsString(MsgId.class, MsgId::new, MsgId::value);
        registerAsString(Instant.class, text -> OffsetDateTime.parse(text).toInstant(), INSTANT_FORMAT::format);
        registerAsString(LocalDate.class, LocalDate::parse, LocalDate::toString);
    }

    private <T> void registerAsString(Class<T> type, Function<String, T> parse, Function<T, String> format) {
        addSerializer(type, serializer(type, format));
        addDeserializer(type, new StdScalarDeserializer<T>(type) {
            @Override
            @SuppressWarnings("unchecked")
            public T deserialize(JsonParser p, DeserializationContext ctxt) {
                if (!p.hasToken(JsonToken.VALUE_STRING)) {
                    return (T) ctxt.handleUnexpectedToken(type, p);
                }
                return parse.apply(p.getString());
            }
        });
    }

    private static <T> StdSerializer<T> serializer(Class<T> type, Function<T, String> format) {
        return new StdSerializer<>(type) {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(format.apply(value));
            }
        };
    }

    /** {@link Money} as {@code amountMinor} + {@code currency}; records unwrap it into their own fields. */
    abstract static class MoneyMixin {

        @JsonCreator
        MoneyMixin(@JsonProperty("amountMinor") long minor, @JsonProperty("currency") Currency currency) {}

        @JsonProperty("amountMinor")
        abstract long minor();

        @JsonProperty("currency")
        abstract Currency currency();
    }
}
