package com.pharmatrack.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.pharmatrack.dto.BatchQrCodeDto;
import com.pharmatrack.entity.Batch;
import com.pharmatrack.exception.ResourceNotFoundException;
import com.pharmatrack.repository.BatchRepository;
import com.pharmatrack.util.AppTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for generating stable, deterministic QR code identities and
 * image payloads for physical production batches.
 * Generating or retrieving QR codes is strictly read-only and never mutates
 * batch lifecycle or manufacturing data.
 */
@Service
public class BatchQrCodeService {

    private static final Logger log = LoggerFactory.getLogger(BatchQrCodeService.class);
    private static final int QR_SIZE = 280;

    private final BatchRepository batchRepository;

    public BatchQrCodeService(BatchRepository batchRepository) {
        this.batchRepository = batchRepository;
    }

    /**
     * Retrieve or generate the stable QR code metadata and base64 PNG data URL for a batch by ID.
     */
    @Transactional(readOnly = true)
    public BatchQrCodeDto getBatchQrCode(Long batchId) {
        if (batchId == null) {
            throw new ResourceNotFoundException("Batch not found with id: null");
        }
        Batch batch = batchRepository.findByIdWithDetails(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with id: " + batchId));

        return buildBatchQrCodeDto(batch);
    }

    /**
     * Retrieve the stable QR code metadata and base64 PNG data URL for a batch by its QR code or batch number.
     */
    @Transactional(readOnly = true)
    public BatchQrCodeDto getBatchQrCodeByIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new ResourceNotFoundException("Batch identifier must not be blank");
        }
        String trimmed = identifier.trim();
        Batch batch = batchRepository.findByIdentifierWithDetails(trimmed)
                .orElseThrow(() -> new ResourceNotFoundException("Batch not found with identifier: " + trimmed));

        return buildBatchQrCodeDto(batch);
    }

    /**
     * Generate base64 data URL for any text payload (e.g. batch QR code identifier or URL).
     */
    public String generateQrImageBase64(String payload) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
            hints.put(EncodeHintType.MARGIN, 2);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(payload, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
            byte[] pngBytes = outputStream.toByteArray();
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);
        } catch (Exception e) {
            log.error("Failed to generate QR image for payload: {}", payload, e);
            throw new IllegalStateException("Failed to render batch QR code: " + e.getMessage(), e);
        }
    }

    private BatchQrCodeDto buildBatchQrCodeDto(Batch batch) {
        String qrCode = batch.getQrCode();
        if (qrCode == null || qrCode.isBlank()) {
            qrCode = "QR-PT-" + batch.getBatchNumber();
        }

        // The QR code encodes the stable opaque batch identifier or direct application route.
        // Never encode JWT tokens or sensitive secrets.
        String dossierUrl = "#/traceability?batchNumber=" + batch.getBatchNumber();
        String qrPayload = qrCode;
        String qrImageBase64 = generateQrImageBase64(qrPayload);

        String orderNumber = (batch.getOrder() != null) ? batch.getOrder().getOrderNumber() : "N/A";
        String productName = (batch.getOrder() != null && batch.getOrder().getProduct() != null)
                ? batch.getOrder().getProduct().getProductName() : "N/A";

        return new BatchQrCodeDto(
                batch.getId(),
                batch.getBatchNumber(),
                qrCode,
                dossierUrl,
                qrImageBase64,
                orderNumber,
                productName,
                AppTime.nowDateTime()
        );
    }
}

