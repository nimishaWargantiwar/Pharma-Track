package com.pharmatrack.controller;

import com.pharmatrack.dto.BatchQrCodeDto;
import com.pharmatrack.dto.BatchTraceabilityDossierDto;
import com.pharmatrack.service.BatchQrCodeService;
import com.pharmatrack.service.BatchTraceabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints for QR-based Batch Traceability and Dossier queries.
 * Read-only endpoints accessible to all authenticated pharmaceutical roles:
 * ADMIN, PRODUCTION_SUPERVISOR, OPERATOR, QC_ANALYST, and QA_REVIEWER.
 */
@RestController
@RequestMapping("/api/batches")
public class BatchTraceabilityController {

    private final BatchQrCodeService qrCodeService;
    private final BatchTraceabilityService traceabilityService;

    public BatchTraceabilityController(BatchQrCodeService qrCodeService,
                                       BatchTraceabilityService traceabilityService) {
        this.qrCodeService = qrCodeService;
        this.traceabilityService = traceabilityService;
    }

    /**
     * Retrieve or generate the stable QR code metadata and base64 image for a batch by primary key ID.
     * GET /api/batches/{id}/qr
     */
    @GetMapping("/{id:\\d+}/qr")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchQrCodeDto> getBatchQrById(@PathVariable Long id) {
        BatchQrCodeDto dto = qrCodeService.getBatchQrCode(id);
        return ResponseEntity.ok(dto);
    }

    /**
     * Resolve and retrieve QR code metadata for a batch by its QR code or batch number.
     * GET /api/batches/qr/{identifier}
     */
    @GetMapping("/qr/{identifier}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchQrCodeDto> getBatchQrByIdentifier(@PathVariable String identifier) {
        BatchQrCodeDto dto = qrCodeService.getBatchQrCodeByIdentifier(identifier);
        return ResponseEntity.ok(dto);
    }

    /**
     * Retrieve the comprehensive read-only Batch Traceability Dossier by batch ID.
     * GET /api/batches/{id}/traceability
     */
    @GetMapping("/{id:\\d+}/traceability")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchTraceabilityDossierDto> getBatchTraceabilityById(@PathVariable Long id) {
        BatchTraceabilityDossierDto dossier = traceabilityService.getTraceabilityDossier(id);
        return ResponseEntity.ok(dossier);
    }

    /**
     * Retrieve the comprehensive read-only Batch Traceability Dossier by batch number,
     * QR code identifier, or primary key.
     * GET /api/batches/traceability/{identifier}
     */
    @GetMapping("/traceability/{identifier}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BatchTraceabilityDossierDto> getBatchTraceabilityByIdentifier(@PathVariable String identifier) {
        BatchTraceabilityDossierDto dossier = traceabilityService.getTraceabilityDossierByIdentifier(identifier);
        return ResponseEntity.ok(dossier);
    }
}

