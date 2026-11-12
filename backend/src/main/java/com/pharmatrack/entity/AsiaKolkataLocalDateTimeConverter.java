package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * JPA attribute converter ensuring every {@link LocalDateTime} entity attribute
 * (anchored to {@code Asia/Kolkata}) is persisted to and read from the database
 * as its exact UTC instant via {@link Timestamp#from(java.time.Instant)} and
 * {@link Timestamp#toInstant()}, without ever depending on {@code TimeZone.getDefault()}.
 *
 * <p>Paired with {@code hibernate.jdbc.time_zone=UTC}, this guarantees that local
 * ({@code Asia/Kolkata}) and production ({@code UTC}) JVMs read and write identical
 * UTC {@code DATETIME(6)} column values with zero 5h30m drift.
 */
@Converter(autoApply = true)
public class AsiaKolkataLocalDateTimeConverter implements AttributeConverter<LocalDateTime, Timestamp> {

    @Override
    public Timestamp convertToDatabaseColumn(LocalDateTime attribute) {
        if (attribute == null) {
            return null;
        }
        return Timestamp.from(AppTime.toInstant(attribute));
    }

    @Override
    public LocalDateTime convertToEntityAttribute(Timestamp dbData) {
        if (dbData == null) {
            return null;
        }
        return AppTime.fromInstant(dbData.toInstant());
    }
}

