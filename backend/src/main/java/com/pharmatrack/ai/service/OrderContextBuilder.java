package com.pharmatrack.ai.service;

import com.pharmatrack.dto.AiSourceReferenceDto;
import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.OrderResponseDto;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transforms verified {@link OrderResponseDto} records into a compact, deterministic,
 * injection-resistant factual context block accompanied by backend-attributed source references.
 *
 * <p>Architectural guarantees:
 * <ul>
 *   <li>Excludes arbitrary free-text user notes to eliminate prompt injection vectors.</li>
 *   <li>Outputs structured, unambiguous key-value records enclosed in verification delimiters.</li>
 *   <li>Escapes dynamic string fields to prevent delimiter collision attacks.</li>
 *   <li>Programmatically generates {@link AiSourceReferenceDto} without LLM involvement.</li>
 * </ul>
 */
@Component
public class OrderContextBuilder {

    public static final String DATA_START_DELIMITER = "<<<VERIFIED_PHARMATRACK_DATA>>>";
    public static final String DATA_END_DELIMITER = "<<</VERIFIED_PHARMATRACK_DATA>>>";
    public static final String ESCAPED_START_DELIMITER = "[ESCAPED_DATA_START_DELIMITER]";
    public static final String ESCAPED_END_DELIMITER = "[ESCAPED_DATA_END_DELIMITER]";

    /**
     * Builds a structured factual context and source reference list from an authorized order response.
     *
     * @param order retrieved manufacturing order DTO
     * @return immutable {@link OrderContext} containing formatted facts and source references
     */
    public OrderContext buildOrderContext(OrderResponseDto order) {
        if (order == null) {
            throw new IllegalArgumentException("OrderResponseDto must not be null");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(DATA_START_DELIMITER).append("\n");
        sb.append("[MANUFACTURING_ORDER]\n");
        sb.append("Order Number: ").append(escapeDelimiters(order.getOrderNumber())).append("\n");
        sb.append("Order Status: ").append(order.getStatus()).append("\n");
        sb.append("Product: ").append(escapeDelimiters(order.getProductName() != null ? order.getProductName() : "N/A"))
                .append(" (Code: ").append(escapeDelimiters(order.getProductCode() != null ? order.getProductCode() : "N/A")).append(")\n");
        sb.append("Recipe: ").append(escapeDelimiters(order.getRecipeCode() != null ? order.getRecipeCode() : "N/A"))
                .append(" (Version: ").append(order.getRecipeVersion() != null ? order.getRecipeVersion() : "N/A").append(")\n");
        sb.append("Planned Quantity: ").append(order.getPlannedQuantity() != null ? order.getPlannedQuantity() : "0")
                .append(" ").append(escapeDelimiters(order.getQuantityUnit() != null ? order.getQuantityUnit() : "")).append("\n");
        sb.append("Target Batch Count: ").append(order.getTargetBatchCount() != null ? order.getTargetBatchCount() : 0).append("\n");
        sb.append("Planned Start Date: ").append(order.getPlannedStartDate() != null ? order.getPlannedStartDate() : "Not set").append("\n");
        sb.append("Planned End Date: ").append(order.getPlannedEndDate() != null ? order.getPlannedEndDate() : "Not set").append("\n");
        sb.append("Actual Start Date: ").append(formatTimestamp(order.getActualStartDate(), "Not started")).append("\n");
        sb.append("Actual End Date: ").append(formatTimestamp(order.getActualEndDate(), "In progress / Not completed")).append("\n");

        List<AiSourceReferenceDto> sourceReferences = new ArrayList<>();

        // 1. Programmatic source reference for the Manufacturing Order
        String orderSummary = "Status: " + order.getStatus()
                + " | Product: " + (order.getProductCode() != null ? order.getProductCode() : "")
                + " | Planned: " + order.getPlannedQuantity() + " " + (order.getQuantityUnit() != null ? order.getQuantityUnit() : "");
        sourceReferences.add(new AiSourceReferenceDto(
                "MANUFACTURING_ORDER",
                order.getOrderNumber(),
                order.getOrderNumber() + " (" + (order.getProductName() != null ? order.getProductName() : "Order") + ")",
                "Manufacturing Orders",
                orderSummary
        ));

        // 2. Associated Child Batches
        List<BatchResponseDto> batches = order.getBatches() != null ? order.getBatches() : List.of();
        sb.append("\n[ASSOCIATED_BATCHES]\n");
        sb.append("Total Batches: ").append(batches.size()).append("\n");

        for (BatchResponseDto batch : batches) {
            String safeBatchUnit = escapeDelimiters(batch.getBatchUnit() != null ? batch.getBatchUnit() : "");
            sb.append("- Batch ").append(escapeDelimiters(batch.getBatchNumber())).append(":")
                    .append(" Status=").append(batch.getStatus())
                    .append(", QA Status=").append(batch.getQaStatus() != null ? batch.getQaStatus() : "PENDING_QA")
                    .append(", Batch Size=").append(batch.getBatchSize()).append(" ").append(safeBatchUnit)
                    .append(", Actual Yield=").append(batch.getActualYield() != null ? batch.getActualYield() + " " + safeBatchUnit : "(not completed)")
                    .append(", Started=").append(formatTimestamp(batch.getStartTime(), "Not started"))
                    .append(", Completed=").append(formatTimestamp(batch.getEndTime(), "In progress / Not completed"))
                    .append("\n");

            // Programmatic source reference for each child batch
            String batchSummary = "Status: " + batch.getStatus()
                    + " | QA: " + (batch.getQaStatus() != null ? batch.getQaStatus() : "PENDING_QA")
                    + " | Size: " + batch.getBatchSize() + " " + (batch.getBatchUnit() != null ? batch.getBatchUnit() : "")
                    + (batch.getActualYield() != null ? " | Yield: " + batch.getActualYield() : "")
                    + (batch.getEndTime() != null ? " | Completed: " + formatSummaryTime(batch.getEndTime()) : "");

            sourceReferences.add(new AiSourceReferenceDto(
                    "BATCH",
                    batch.getBatchNumber(),
                    batch.getBatchNumber() + " (" + batch.getStatus() + ")",
                    "Production Execution",
                    batchSummary
            ));
        }

        sb.append(DATA_END_DELIMITER);

        return new OrderContext(sb.toString(), sourceReferences);
    }

    private static final DateTimeFormatter TIME_12H_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

    /**
     * Formats a {@link LocalDateTime} into an explicit, timezone-annotated string
     * displaying both exact ISO time and 12-hour AM/PM time in Asia/Kolkata (+05:30).
     *
     * @param dateTime local timestamp anchored in Asia/Kolkata
     * @param fallback default description if timestamp is null
     * @return formatted timestamp string, e.g. "2026-11-13T02:29:15 (02:29 AM IST)"
     */
    public static String formatTimestamp(LocalDateTime dateTime, String fallback) {
        if (dateTime == null) {
            return fallback;
        }
        return dateTime.toString() + " (" + dateTime.format(TIME_12H_FORMATTER) + " IST)";
    }

    /**
     * Formats a {@link LocalDateTime} into a compact summary time string for source reference pills.
     *
     * @param dateTime local timestamp anchored in Asia/Kolkata
     * @return 12-hour time string with IST suffix, e.g. "02:29 AM IST"
     */
    public static String formatSummaryTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(TIME_12H_FORMATTER) + " IST";
    }

    /**
     * Escapes occurrences of context opening and closing delimiters within untrusted data values
     * to prevent delimiter injection attacks from terminating or forging context sections.
     *
     * @param value raw string value from database or DTO
     * @return safely escaped string, or null if input is null
     */
    public static String escapeDelimiters(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace(DATA_START_DELIMITER, ESCAPED_START_DELIMITER)
                .replace(DATA_END_DELIMITER, ESCAPED_END_DELIMITER);
    }
}
