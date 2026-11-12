package com.pharmatrack.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Shared application time helpers anchored to the plant/system timezone ({@code Asia/Kolkata}).
 *
 * <p>Contract:
 * <ul>
 *   <li>Application timestamps use {@link LocalDateTime} anchored in {@code Asia/Kolkata} (+05:30).</li>
 *   <li>Database {@code DATETIME(6)} columns persist exact UTC instants via JPA conversion + UTC JDBC timezone.</li>
 *   <li>JSON wire payloads serialize timestamps with explicit {@code +05:30} offset and accept both
 *       offset-aware ({@code Z}, {@code +HH:MM}, {@code -HH:MM}) and offset-free ISO datetimes
 *       (interpreted strictly in {@code Asia/Kolkata}).</li>
 *   <li>Calendar {@link LocalDate} fields represent timezone-independent business dates ({@code YYYY-MM-DD}).</li>
 * </ul>
 */
public final class AppTime {

    public static final ZoneId APP_ZONE_ID = ZoneId.of("Asia/Kolkata");
    public static final ZoneOffset APP_ZONE_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    private AppTime() {
    }

    public static LocalDateTime nowDateTime() {
        return LocalDateTime.now(APP_ZONE_ID);
    }

    public static LocalDate nowDate() {
        return LocalDate.now(APP_ZONE_ID);
    }

    public static OffsetDateTime nowOffsetDateTime() {
        return Instant.now().atOffset(APP_ZONE_OFFSET);
    }

    public static String nowIsoOffsetString() {
        return nowOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(APP_ZONE_ID).toOffsetDateTime();
    }

    public static Instant toInstant(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(APP_ZONE_ID).toInstant();
    }

    public static LocalDateTime fromInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, APP_ZONE_ID);
    }

    public static String formatIsoTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return toOffsetDateTime(dateTime).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Parses an ISO-8601 timestamp string into an {@code Asia/Kolkata} {@link LocalDateTime}.
     * Offset-bearing strings ({@code Z}, {@code +05:30}, {@code -05:00}, named zones) are converted
     * to the exact instant in {@code Asia/Kolkata}; offset-free strings are interpreted directly
     * as wall-clock time in {@code Asia/Kolkata}.
     */
    public static LocalDateTime parseToAppLocalDateTime(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(APP_ZONE_ID)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Try zoned or offset-free ISO formats below
        }
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                    .withZoneSameInstant(APP_ZONE_ID)
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Fall through to offset-free LocalDateTime
        }
        return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public static JavaTimeModule createAsiaKolkataJavaTimeModule() {
        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(LocalDateTime.class, new AsiaKolkataLocalDateTimeSerializer());
        module.addDeserializer(LocalDateTime.class, new AsiaKolkataLocalDateTimeDeserializer());
        return module;
    }

    public static ObjectMapper configureObjectMapper(ObjectMapper mapper) {
        mapper.registerModule(createAsiaKolkataJavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public static ObjectMapper createObjectMapper() {
        return configureObjectMapper(new ObjectMapper());
    }

    public static final class AsiaKolkataLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(formatIsoTimestamp(value));
        }
    }

    public static final class AsiaKolkataLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.START_ARRAY) {
                return LocalDateTimeDeserializer.INSTANCE.deserialize(p, ctxt);
            }
            if (token == JsonToken.VALUE_STRING) {
                String raw = p.getValueAsString();
                if (raw == null || raw.trim().isEmpty()) {
                    return null;
                }
                try {
                    return parseToAppLocalDateTime(raw);
                } catch (DateTimeParseException ex) {
                    throw InvalidFormatException.from(
                            p,
                            "Invalid ISO-8601 timestamp for Asia/Kolkata conversion: '" + raw + "'",
                            raw,
                            LocalDateTime.class
                    );
                }
            }
            return (LocalDateTime) ctxt.handleUnexpectedToken(LocalDateTime.class, p);
        }
    }
}

