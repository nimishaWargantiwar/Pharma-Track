package com.pharmatrack.dto;

import com.pharmatrack.entity.BatchQualityStatus;
import com.pharmatrack.entity.BatchStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response payload DTO representing a physical production Batch
 * with parent order summary and execution metrics.
 */
public class BatchResponseDto {

    private Long id;
    private String batchNumber;
    private String qrCode;
    private Long orderId;
    private String orderNumber;
    private BigDecimal batchSize;
    private String batchUnit;
    private BigDecimal actualYield;
    private BatchStatus status;
    private BatchQualityStatus qaStatus;
    private LocalDate manufactureDate;
    private LocalDate expiryDate;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public BatchResponseDto() {
    }

    public BatchResponseDto(Long id, String batchNumber, Long orderId, String orderNumber,
                            BigDecimal batchSize, String batchUnit, BigDecimal actualYield,
                            BatchStatus status, LocalDate manufactureDate, LocalDate expiryDate,
                            LocalDateTime startTime, LocalDateTime endTime,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, batchNumber, orderId, orderNumber, batchSize, batchUnit, actualYield,
                status, null, manufactureDate, expiryDate, startTime, endTime, createdAt, updatedAt);
    }

    public BatchResponseDto(Long id, String batchNumber, Long orderId, String orderNumber,
                            BigDecimal batchSize, String batchUnit, BigDecimal actualYield,
                            BatchStatus status, BatchQualityStatus qaStatus,
                            LocalDate manufactureDate, LocalDate expiryDate,
                            LocalDateTime startTime, LocalDateTime endTime,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.batchNumber = batchNumber;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.batchSize = batchSize;
        this.batchUnit = batchUnit;
        this.actualYield = actualYield;
        this.status = status;
        this.qaStatus = qaStatus;
        this.manufactureDate = manufactureDate;
        this.expiryDate = expiryDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public BigDecimal getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(BigDecimal batchSize) {
        this.batchSize = batchSize;
    }

    public String getBatchUnit() {
        return batchUnit;
    }

    public void setBatchUnit(String batchUnit) {
        this.batchUnit = batchUnit;
    }

    public BigDecimal getActualYield() {
        return actualYield;
    }

    public void setActualYield(BigDecimal actualYield) {
        this.actualYield = actualYield;
    }

    public BatchStatus getStatus() {
        return status;
    }

    public void setStatus(BatchStatus status) {
        this.status = status;
    }

    public BatchQualityStatus getQaStatus() {
        return qaStatus;
    }

    public void setQaStatus(BatchQualityStatus qaStatus) {
        this.qaStatus = qaStatus;
    }

    public LocalDate getManufactureDate() {
        return manufactureDate;
    }

    public void setManufactureDate(LocalDate manufactureDate) {
        this.manufactureDate = manufactureDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

