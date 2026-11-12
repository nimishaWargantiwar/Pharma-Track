package com.pharmatrack;

import com.pharmatrack.dto.BatchResponseDto;
import com.pharmatrack.dto.BatchStatusUpdateDto;
import com.pharmatrack.dto.ErrorResponse;
import com.pharmatrack.dto.OrderRequestDto;
import com.pharmatrack.dto.OrderResponseDto;
import com.pharmatrack.dto.OrderStatusUpdateDto;
import com.pharmatrack.entity.BatchStatus;
import com.pharmatrack.entity.OrderStatus;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.InvalidOrderException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for Manufacturing Order & Batch DTO validations,
 * custom InvalidOrderException, and GlobalExceptionHandler response mapping.
 */
class OrderDtoValidationTest {

    private static Validator validator;
    private static GlobalExceptionHandler exceptionHandler;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        exceptionHandler = new GlobalExceptionHandler();
    }

    private OrderRequestDto createValidOrderRequest() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        return new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("500.0000"),
                "kg",
                today,
                today.plusDays(10),
                "Produce two batches of 250 kg each"
        );
    }

    // -------------------------------------------------------------------------
    // 1. Valid OrderRequestDto
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Valid OrderRequestDto passes all validations")
    void validOrderRequestDtoPassesValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Valid order request should have no violations");
    }

    @Test
    @DisplayName("2. Valid OrderRequestDto with null optional fields passes validation")
    void validOrderRequestWithNullOptionalFieldsPassesValidation() {
        OrderRequestDto dto = new OrderRequestDto(
                1L,
                10L,
                new BigDecimal("250.0000"),
                "kg",
                null, // optional start date
                null, // optional end date
                null  // optional notes
        );
        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Order request with null optional fields should pass validation");
    }

    // -------------------------------------------------------------------------
    // 2. Product ID Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. Null productId fails validation")
    void nullProductIdFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setProductId(null);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("productId", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("4. Zero productId fails validation")
    void zeroProductIdFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setProductId(0L);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("productId", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("5. Negative productId fails validation")
    void negativeProductIdFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setProductId(-5L);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("productId", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 3. Recipe ID Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Null recipeId fails validation")
    void nullRecipeIdFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setRecipeId(null);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("recipeId", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("7. Zero recipeId fails validation")
    void zeroRecipeIdFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setRecipeId(0L);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("recipeId", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("8. Negative recipeId fails validation")
    void negativeRecipeIdFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setRecipeId(-1L);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("recipeId", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 4. Planned Quantity Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. Null plannedQuantity fails validation")
    void nullPlannedQuantityFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedQuantity(null);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("plannedQuantity", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("10. Zero plannedQuantity fails validation")
    void zeroPlannedQuantityFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedQuantity(BigDecimal.ZERO);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("plannedQuantity", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("11. Negative plannedQuantity fails validation")
    void negativePlannedQuantityFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedQuantity(new BigDecimal("-100.0000"));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("plannedQuantity", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 5. Quantity Unit Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("12. Blank quantityUnit fails validation")
    void blankQuantityUnitFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setQuantityUnit("   ");

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("quantityUnit", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("13. Null quantityUnit fails validation")
    void nullQuantityUnitFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setQuantityUnit(null);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("quantityUnit", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("14. Oversized quantityUnit (> 50 chars) fails validation")
    void oversizedQuantityUnitFailsValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setQuantityUnit("U".repeat(51));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("quantityUnit", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 6. Planned Date Range Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("15. plannedEndDate earlier than plannedStartDate fails validation")
    void plannedEndDateEarlierThanStartDateFailsValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today.plusDays(10));
        dto.setPlannedEndDate(today.plusDays(2)); // earlier!

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("plannedEndDateValid", violations.iterator().next().getPropertyPath().toString());
        assertEquals("Planned end date must not be earlier than planned start date",
                violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("16. plannedEndDate same day as plannedStartDate passes validation")
    void plannedEndDateSameDayAsStartDatePassesValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today.plusDays(5));
        dto.setPlannedEndDate(today.plusDays(5));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Same-day start and end date should pass validation");
    }

    @Test
    @DisplayName("17. plannedEndDate later than plannedStartDate passes validation")
    void plannedEndDateLaterThanStartDatePassesValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today);
        dto.setPlannedEndDate(today.plusDays(15));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "End date after start date should pass validation");
    }

    @Test
    @DisplayName("17a. End date 1 day before future start date fails validation")
    void plannedEndDateNov14EarlierThanNov15FailsValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today.plusDays(5));
        dto.setPlannedEndDate(today.plusDays(4));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("plannedEndDateValid", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("17b. End date 1 day after future start date passes validation")
    void plannedEndDateNov16AfterNov15PassesValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today.plusDays(5));
        dto.setPlannedEndDate(today.plusDays(6));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "End date after start date should pass validation");
    }

    @Test
    @DisplayName("17c. Only plannedStartDate provided passes validation")
    void onlyPlannedStartDateProvidedPassesValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today.plusDays(5));
        dto.setPlannedEndDate(null);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Only start date provided should pass validation");
    }

    @Test
    @DisplayName("17d. Only plannedEndDate provided passes validation")
    void onlyPlannedEndDateProvidedPassesValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(null);
        dto.setPlannedEndDate(today.plusDays(4));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Only end date provided should pass validation");
    }

    @Test
    @DisplayName("17e. plannedStartDate yesterday fails validation")
    void plannedStartDateYesterdayFailsValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today.minusDays(1));
        dto.setPlannedEndDate(today.plusDays(5));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("plannedStartDateValid", violations.iterator().next().getPropertyPath().toString());
        assertEquals("Planned start date cannot be earlier than today",
                violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("17f. plannedStartDate today passes validation")
    void plannedStartDateTodayPassesValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today);
        dto.setPlannedEndDate(today.plusDays(2));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Today plannedStartDate should pass validation");
    }

    @Test
    @DisplayName("17g. plannedStartDate tomorrow passes validation")
    void plannedStartDateTomorrowPassesValidation() {
        LocalDate today = LocalDate.now(OrderRequestDto.APP_ZONE_ID);
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(today.plusDays(1));
        dto.setPlannedEndDate(today.plusDays(3));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Tomorrow plannedStartDate should pass validation");
    }

    @Test
    @DisplayName("17h. Empty (null) plannedStartDate passes validation")
    void emptyPlannedStartDatePassesValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setPlannedStartDate(null);
        dto.setPlannedEndDate(null);

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Null plannedStartDate should pass validation");
    }

    @Test
    @DisplayName("17i. PlannedStartDate validation uses Asia/Kolkata local date instead of UTC")
    void plannedStartDateUsesAsiaKolkataTimezoneAtUtcBoundary() {
        // 2026-11-09T18:30:00Z is 2026-11-10T00:00:00+05:30 in Asia/Kolkata
        Clock midnightIstClockInUtc = Clock.fixed(Instant.parse("2026-11-09T18:30:00Z"), ZoneOffset.UTC);

        assertFalse(OrderRequestDto.isValidStartDate(LocalDate.of(2026, 11, 9), midnightIstClockInUtc),
                "Nov 9 is yesterday in Asia/Kolkata at 18:30Z and must be rejected");
        assertTrue(OrderRequestDto.isValidStartDate(LocalDate.of(2026, 11, 10), midnightIstClockInUtc),
                "Nov 10 is already today in Asia/Kolkata at 18:30Z and must be allowed");
        assertTrue(OrderRequestDto.isValidStartDate(LocalDate.of(2026, 11, 11), midnightIstClockInUtc),
                "Nov 11 is tomorrow in Asia/Kolkata at 18:30Z and must be allowed");
    }

    // -------------------------------------------------------------------------
    // 7. Notes Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("18. Valid optional notes pass validation")
    void validOptionalNotesPassValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setNotes("Valid notes under character limit");

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("19. Oversized notes (> 2000 chars) fail validation")
    void oversizedNotesFailValidation() {
        OrderRequestDto dto = createValidOrderRequest();
        dto.setNotes("N".repeat(2001));

        Set<ConstraintViolation<OrderRequestDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("notes", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 8. OrderStatusUpdateDto Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("20. Valid OrderStatusUpdateDto passes validation")
    void validOrderStatusUpdateDtoPassesValidation() {
        OrderStatusUpdateDto dto = new OrderStatusUpdateDto(OrderStatus.RELEASED);
        Set<ConstraintViolation<OrderStatusUpdateDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Valid OrderStatusUpdateDto should pass validation");
    }

    @Test
    @DisplayName("21. Null status in OrderStatusUpdateDto fails validation")
    void nullStatusInOrderStatusUpdateDtoFailsValidation() {
        OrderStatusUpdateDto dto = new OrderStatusUpdateDto(null);
        Set<ConstraintViolation<OrderStatusUpdateDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("status", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 9. BatchStatusUpdateDto Validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("22. Valid BatchStatusUpdateDto with null actualYield passes validation")
    void validBatchStatusUpdateDtoWithNullYieldPassesValidation() {
        BatchStatusUpdateDto dto = new BatchStatusUpdateDto(BatchStatus.IN_PROGRESS, null);
        Set<ConstraintViolation<BatchStatusUpdateDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Optional actualYield can be null");
    }

    @Test
    @DisplayName("23. Valid BatchStatusUpdateDto with positive actualYield passes validation")
    void validBatchStatusUpdateDtoWithPositiveYieldPassesValidation() {
        BatchStatusUpdateDto dto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("248.5000"));
        Set<ConstraintViolation<BatchStatusUpdateDto>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty(), "Positive actualYield should pass validation");
    }

    @Test
    @DisplayName("24. Zero actualYield in BatchStatusUpdateDto fails validation")
    void zeroActualYieldInBatchStatusUpdateDtoFailsValidation() {
        BatchStatusUpdateDto dto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, BigDecimal.ZERO);
        Set<ConstraintViolation<BatchStatusUpdateDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("actualYield", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("25. Negative actualYield in BatchStatusUpdateDto fails validation")
    void negativeActualYieldInBatchStatusUpdateDtoFailsValidation() {
        BatchStatusUpdateDto dto = new BatchStatusUpdateDto(BatchStatus.COMPLETED, new BigDecimal("-50.0000"));
        Set<ConstraintViolation<BatchStatusUpdateDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("actualYield", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    @DisplayName("26. Null status in BatchStatusUpdateDto fails validation")
    void nullStatusInBatchStatusUpdateDtoFailsValidation() {
        BatchStatusUpdateDto dto = new BatchStatusUpdateDto(null, new BigDecimal("250.0000"));
        Set<ConstraintViolation<BatchStatusUpdateDto>> violations = validator.validate(dto);
        assertEquals(1, violations.size());
        assertEquals("status", violations.iterator().next().getPropertyPath().toString());
    }

    // -------------------------------------------------------------------------
    // 10. InvalidOrderException and GlobalExceptionHandler
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("27. InvalidOrderException preserves message and cause")
    void invalidOrderExceptionPreservesMessageAndCause() {
        InvalidOrderException ex = new InvalidOrderException("Planned quantity must be a multiple of batch size");
        assertEquals("Planned quantity must be a multiple of batch size", ex.getMessage());

        Throwable cause = new IllegalArgumentException("Invalid argument");
        InvalidOrderException exWithCause = new InvalidOrderException("Failed order rule", cause);
        assertEquals("Failed order rule", exWithCause.getMessage());
        assertEquals(cause, exWithCause.getCause());
    }

    @Test
    @DisplayName("28. GlobalExceptionHandler maps InvalidOrderException to HTTP 400 Bad Request")
    void globalExceptionHandlerMapsInvalidOrderExceptionTo400() {
        InvalidOrderException ex = new InvalidOrderException("Cannot create order for inactive product");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleInvalidOrderException(ex);

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getStatusCode().value());

        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(400, body.getStatus());
        assertEquals("Bad Request", body.getError());
        assertEquals("Cannot create order for inactive product", body.getMessage());
    }

    // -------------------------------------------------------------------------
    // 11. Response DTOs POJO Verification
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("29. OrderResponseDto and BatchResponseDto store and retrieve all fields correctly")
    void responseDtosStoreFieldsCorrectly() {
        LocalDateTime now = LocalDateTime.now();
        BatchResponseDto batchDto = new BatchResponseDto(
                50L,
                "BAT-2026-0001-01",
                100L,
                "MO-2026-0001",
                new BigDecimal("250.0000"),
                "kg",
                new BigDecimal("248.0000"),
                BatchStatus.COMPLETED,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2028, 11, 10),
                now.minusHours(4),
                now,
                now.minusDays(1),
                now
        );

        assertEquals(50L, batchDto.getId());
        assertEquals("BAT-2026-0001-01", batchDto.getBatchNumber());
        assertEquals(100L, batchDto.getOrderId());
        assertEquals("MO-2026-0001", batchDto.getOrderNumber());
        assertEquals(new BigDecimal("250.0000"), batchDto.getBatchSize());
        assertEquals("kg", batchDto.getBatchUnit());
        assertEquals(new BigDecimal("248.0000"), batchDto.getActualYield());
        assertEquals(BatchStatus.COMPLETED, batchDto.getStatus());
        assertEquals(LocalDate.of(2026, 11, 10), batchDto.getManufactureDate());
        assertEquals(LocalDate.of(2028, 11, 10), batchDto.getExpiryDate());

        OrderResponseDto orderDto = new OrderResponseDto(
                100L,
                "MO-2026-0001",
                1L,
                "AMOX-250",
                "Amoxicillin Capsules",
                10L,
                "RCP-AMOX-250-V3",
                3,
                new BigDecimal("500.0000"),
                "kg",
                2,
                OrderStatus.IN_PROGRESS,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 20),
                now.minusHours(4),
                null,
                "Hospital production run",
                List.of(batchDto),
                now.minusDays(1),
                now
        );

        assertEquals(100L, orderDto.getId());
        assertEquals("MO-2026-0001", orderDto.getOrderNumber());
        assertEquals(1L, orderDto.getProductId());
        assertEquals("AMOX-250", orderDto.getProductCode());
        assertEquals("Amoxicillin Capsules", orderDto.getProductName());
        assertEquals(10L, orderDto.getRecipeId());
        assertEquals("RCP-AMOX-250-V3", orderDto.getRecipeCode());
        assertEquals(3, orderDto.getRecipeVersion());
        assertEquals(new BigDecimal("500.0000"), orderDto.getPlannedQuantity());
        assertEquals("kg", orderDto.getQuantityUnit());
        assertEquals(2, orderDto.getTargetBatchCount());
        assertEquals(OrderStatus.IN_PROGRESS, orderDto.getStatus());
        assertEquals(1, orderDto.getBatches().size());
        assertEquals("BAT-2026-0001-01", orderDto.getBatches().get(0).getBatchNumber());
    }
}

