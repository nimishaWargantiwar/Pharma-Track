package com.pharmatrack.ai.controller;

import com.pharmatrack.ai.service.AiCopilotService;
import com.pharmatrack.dto.AiQueryRequestDto;
import com.pharmatrack.dto.AiQueryResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints for PharmaTrack AI Copilot advisory queries.
 *
 * <p>Authentication is enforced strictly server-side; all five authenticated MES roles
 * (ADMIN, PRODUCTION_SUPERVISOR, OPERATOR, QC_ANALYST, and QA_REVIEWER) may submit
 * queries regarding authorized manufacturing orders.</p>
 */
@RestController
@RequestMapping("/api/ai")
public class AiCopilotController {

    private final AiCopilotService aiCopilotService;

    public AiCopilotController(AiCopilotService aiCopilotService) {
        this.aiCopilotService = aiCopilotService;
    }

    /**
     * Submit an advisory natural-language query to PharmaTrack AI Copilot.
     * POST /api/ai/query
     */
    @PostMapping("/query")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AiQueryResponseDto> query(@Valid @RequestBody AiQueryRequestDto requestDto) {
        AiQueryResponseDto response = aiCopilotService.query(requestDto);
        return ResponseEntity.ok(response);
    }
}
