package com.pharmatrack.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.IOException;

/**
 * Data Transfer Object for manufacturing steps within a Recipe.
 */
public class RecipeStepDto {

    @NotNull(message = "Step number is mandatory")
    @Min(value = 1, message = "Step number must be greater than zero")
    @JsonDeserialize(using = StrictStepNumberDeserializer.class)
    private Integer stepNumber;

    @NotBlank(message = "Operation name is mandatory")
    @Size(max = 255, message = "Operation name must not exceed 255 characters")
    private String operationName;

    @NotBlank(message = "Instructions are mandatory")
    private String instructions;

    public RecipeStepDto() {
    }

    public RecipeStepDto(Integer stepNumber, String operationName, String instructions) {
        this.stepNumber = stepNumber;
        this.operationName = operationName;
        this.instructions = instructions;
    }

    public Integer getStepNumber() {
        return stepNumber;
    }

    public void setStepNumber(Integer stepNumber) {
        this.stepNumber = stepNumber;
    }

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    /**
     * Strict JSON deserializer for {@code stepNumber} that rejects decimals (even whole floats like 1.0)
     * and non-numeric values rather than silently coercing or truncating floats to integers.
     */
    public static class StrictStepNumberDeserializer extends JsonDeserializer<Integer> {

        public StrictStepNumberDeserializer() {
        }

        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_NUMBER_INT) {
                try {
                    return p.getIntValue();
                } catch (IOException ex) {
                    throw InvalidFormatException.from(p, "Step number must be a valid integer", p.getNumberValue(), Integer.class);
                }
            }
            if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                throw InvalidFormatException.from(p, "Step number must be a positive integer", p.getDecimalValue(), Integer.class);
            }
            if (token == JsonToken.VALUE_STRING) {
                String raw = p.getText() != null ? p.getText().trim() : "";
                if (!raw.matches("^-?\\d+$")) {
                    throw InvalidFormatException.from(p, "Step number must be a positive integer", p.getText(), Integer.class);
                }
                try {
                    return Integer.parseInt(raw);
                } catch (NumberFormatException ex) {
                    throw InvalidFormatException.from(p, "Step number must be a valid integer", p.getText(), Integer.class);
                }
            }
            return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
        }
    }
}

