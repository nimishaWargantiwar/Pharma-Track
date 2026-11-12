package com.pharmatrack.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.pharmatrack.util.AppTime;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Global Spring Boot Jackson component standardizing {@link LocalDateTime} JSON
 * serialization and deserialization to {@code Asia/Kolkata} ({@code +05:30}).
 */
@JsonComponent
public class AsiaKolkataJacksonComponent {

    public static class Serializer extends JsonSerializer<LocalDateTime> {

        private final AppTime.AsiaKolkataLocalDateTimeSerializer delegate =
                new AppTime.AsiaKolkataLocalDateTimeSerializer();

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            delegate.serialize(value, gen, serializers);
        }
    }

    public static class Deserializer extends JsonDeserializer<LocalDateTime> {

        private final AppTime.AsiaKolkataLocalDateTimeDeserializer delegate =
                new AppTime.AsiaKolkataLocalDateTimeDeserializer();

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return delegate.deserialize(p, ctxt);
        }
    }
}

