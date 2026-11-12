package com.pharmatrack.dto;

import java.time.LocalDateTime;

/**
 * Data transfer object representing the generated QR code identity and metadata
 * for a manufacturing batch.
 */
public class BatchQrCodeDto {

    private Long batchId;
    private String batchNumber;
    private String qrCode;
    private String dossierUrl;
    private String qrImageBase64;
    private String orderNumber;
    private String productName;
    private LocalDateTime generatedAt;

    public BatchQrCodeDto() {
    }

    public BatchQrCodeDto(Long batchId, String batchNumber, String qrCode, String dossierUrl,
                          String qrImageBase64, String orderNumber, String productName,
                          LocalDateTime generatedAt) {
        this.batchId = batchId;
        this.batchNumber = batchNumber;
        this.qrCode = qrCode;
        this.dossierUrl = dossierUrl;
        this.qrImageBase64 = qrImageBase64;
        this.orderNumber = orderNumber;
        this.productName = productName;
        this.generatedAt = generatedAt;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
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

    public String getDossierUrl() {
        return dossierUrl;
    }

    public void setDossierUrl(String dossierUrl) {
        this.dossierUrl = dossierUrl;
    }

    public String getQrImageBase64() {
        return qrImageBase64;
    }

    public void setQrImageBase64(String qrImageBase64) {
        this.qrImageBase64 = qrImageBase64;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(LocalDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }
}

