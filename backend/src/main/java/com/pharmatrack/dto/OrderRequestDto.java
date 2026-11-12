package com.pharmatrack.dto;

import com.pharmatrack.util.AppTime;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Request payload DTO for creating a Manufacturing Order.
 * Order number, recipe version snapshot, batch count, status, and batch generation
 * are managed strictly by the backend service layer.
 */
public class OrderRequestDto {

    public static final ZoneId APP_ZONE_ID = AppTime.APP_ZONE_ID;

    @NotNull(message = "Product ID is mandatory")
    @Positive(message = "Product ID must be greater than zero")
    private Long productId;

    @NotNull(message = "Recipe ID is mandatory")
    @Positive(message = "Recipe ID must be greater than zero")
    private Long recipeId;

    @NotNull(message = "Planned quantity is mandatory")
    @Positive(message = "Planned quantity must be greater than zero")
    private BigDecimal plannedQuantity;

    @NotBlank(message = "Quantity unit is mandatory")
    @Size(max = 50, message = "Quantity unit must not exceed 50 characters")
    private String quantityUnit;

    private LocalDate plannedStartDate;

    private LocalDate plannedEndDate;

    @Size(max = 2000, message = "Notes must not exceed 2000 characters")
    private String notes;

    public OrderRequestDto() {
    }

    public OrderRequestDto(Long productId, Long recipeId, BigDecimal plannedQuantity, String quantityUnit,
                           LocalDate plannedStartDate, LocalDate plannedEndDate, String notes) {
        this.productId = productId;
        this.recipeId = recipeId;
        this.plannedQuantity = plannedQuantity;
        this.quantityUnit = quantityUnit;
        this.plannedStartDate = plannedStartDate;
        this.plannedEndDate = plannedEndDate;
        this.notes = notes;
    }

    /**
     * Validates that planned start date (when provided) is not earlier than today
     * in the application timezone (Asia/Kolkata).
     */
    @AssertTrue(message = "Planned start date cannot be earlier than today")
    public boolean isPlannedStartDateValid() {
        return isValidStartDate(plannedStartDate, Clock.system(APP_ZONE_ID));
    }

    /**
     * Timezone-aware start-date helper allowing deterministic clock-based testing.
     */
    public static boolean isValidStartDate(LocalDate plannedStartDate, Clock clock) {
        if (plannedStartDate == null) {
            return true;
        }
        LocalDate today = LocalDate.now(clock.withZone(APP_ZONE_ID));
        return !plannedStartDate.isBefore(today);
    }

    /**
     * Cross-field validation ensuring planned end date is not scheduled before planned start date.
     */
    @AssertTrue(message = "Planned end date must not be earlier than planned start date")
    public boolean isPlannedEndDateValid() {
        if (plannedStartDate == null || plannedEndDate == null) {
            return true;
        }
        return !plannedEndDate.isBefore(plannedStartDate);
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getRecipeId() {
        return recipeId;
    }

    public void setRecipeId(Long recipeId) {
        this.recipeId = recipeId;
    }

    public BigDecimal getPlannedQuantity() {
        return plannedQuantity;
    }

    public void setPlannedQuantity(BigDecimal plannedQuantity) {
        this.plannedQuantity = plannedQuantity;
    }

    public String getQuantityUnit() {
        return quantityUnit;
    }

    public void setQuantityUnit(String quantityUnit) {
        this.quantityUnit = quantityUnit;
    }

    public LocalDate getPlannedStartDate() {
        return plannedStartDate;
    }

    public void setPlannedStartDate(LocalDate plannedStartDate) {
        this.plannedStartDate = plannedStartDate;
    }

    public LocalDate getPlannedEndDate() {
        return plannedEndDate;
    }

    public void setPlannedEndDate(LocalDate plannedEndDate) {
        this.plannedEndDate = plannedEndDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

