package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a physical production Batch in PharmaTrack.
 * Belongs to a parent Manufacturing Order; product and recipe are accessed
 * through the parent order to avoid redundant database relationships.
 */
@Entity
@Table(name = "batches", uniqueConstraints = {
        @UniqueConstraint(name = "uk_batch_number", columnNames = "batch_number")
})
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Batch number is mandatory")
    @Size(max = 50, message = "Batch number must not exceed 50 characters")
    @Column(name = "batch_number", nullable = false, unique = true, length = 50)
    private String batchNumber;

    @Size(max = 100, message = "QR code must not exceed 100 characters")
    @Column(name = "qr_code", unique = true, length = 100)
    private String qrCode;

    @NotNull(message = "Order reference is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private ManufacturingOrder order;

    @NotNull(message = "Batch size is mandatory")
    @Positive(message = "Batch size must be greater than zero")
    @Column(name = "batch_size", nullable = false, precision = 12, scale = 4)
    private BigDecimal batchSize;

    @NotBlank(message = "Batch unit is mandatory")
    @Size(max = 50, message = "Batch unit must not exceed 50 characters")
    @Column(name = "batch_unit", nullable = false, length = 50)
    private String batchUnit;

    @Column(name = "actual_yield", precision = 12, scale = 4)
    private BigDecimal actualYield;

    @NotNull(message = "Status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "qa_status", length = 30)
    private BatchQualityStatus qaStatus;

    @Column(name = "manufacture_date")
    private LocalDate manufactureDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Batch() {
    }

    public Batch(String batchNumber, ManufacturingOrder order, BigDecimal batchSize, String batchUnit,
                 BatchStatus status, LocalDate manufactureDate, LocalDate expiryDate) {
        this.batchNumber = batchNumber;
        this.order = order;
        this.batchSize = batchSize;
        this.batchUnit = batchUnit;
        this.status = status;
        this.manufactureDate = manufactureDate;
        this.expiryDate = expiryDate;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = BatchStatus.PLANNED;
        }
        if (this.qrCode == null && this.batchNumber != null) {
            this.qrCode = "QR-PT-" + this.batchNumber;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
    }

    // Getters and Setters

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

    public ManufacturingOrder getOrder() {
        return order;
    }

    public void setOrder(ManufacturingOrder order) {
        this.order = order;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Batch)) return false;
        Batch other = (Batch) o;
        String thisBatchNumber = getBatchNumber();
        String otherBatchNumber = other.getBatchNumber();
        if (thisBatchNumber == null || otherBatchNumber == null) {
            return false;
        }
        return Objects.equals(thisBatchNumber, otherBatchNumber);
    }

    @Override
    public int hashCode() {
        return Batch.class.hashCode();
    }

    @Override
    public String toString() {
        return "Batch{" +
                "id=" + id +
                ", batchNumber='" + batchNumber + '\'' +
                ", batchSize=" + batchSize +
                ", batchUnit='" + batchUnit + '\'' +
                ", status=" + status +
                ", actualYield=" + actualYield +
                ", manufactureDate=" + manufactureDate +
                ", expiryDate=" + expiryDate +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

