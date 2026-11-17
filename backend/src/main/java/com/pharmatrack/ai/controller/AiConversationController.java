package com.pharmatrack.ai.controller;

import com.pharmatrack.ai.service.AiConversationService;
import com.pharmatrack.dto.CopilotConversationCreateRequestDto;
import com.pharmatrack.dto.CopilotConversationDetailDto;
import com.pharmatrack.dto.CopilotConversationRenameRequestDto;
import com.pharmatrack.dto.CopilotConversationSummaryDto;
import com.pharmatrack.dto.CopilotMessageResponseDto;
import com.pharmatrack.dto.CopilotSubmissionRequestDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller managing persistent multi-turn AI Copilot conversation threads.
 *
 * <p>Security Contract:
 * <ul>
 *   <li>All endpoints require valid authentication across all MES roles.</li>
 *   <li>User identity is resolved strictly server-side; client-supplied identity is rejected.</li>
 *   <li>Users may only access, rename, or delete their own conversation threads.</li>
 *   <li>Unauthorized or nonexistent conversation IDs return 404 to avoid information disclosure.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/conversations")
public class AiConversationController {

    private final AiConversationService conversationService;

    public AiConversationController(AiConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * Create a new conversation thread for the authenticated user.
     * POST /api/ai/conversations
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CopilotConversationSummaryDto> createConversation(
            @Valid @RequestBody CopilotConversationCreateRequestDto requestDto) {
        CopilotConversationSummaryDto summary = conversationService.createConversation(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    /**
     * List all conversations belonging to the authenticated user, ordered by most recently updated.
     * GET /api/ai/conversations
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CopilotConversationSummaryDto>> listConversations(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        List<CopilotConversationSummaryDto> conversations = conversationService.listConversations(page, size);
        return ResponseEntity.ok(conversations);
    }

    /**
     * Retrieve complete conversation details and chronological message transcript for an owned thread.
     * GET /api/ai/conversations/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CopilotConversationDetailDto> getConversationDetail(@PathVariable Long id) {
        CopilotConversationDetailDto detail = conversationService.getConversationDetail(id);
        return ResponseEntity.ok(detail);
    }

    /**
     * Rename an owned conversation thread.
     * PATCH /api/ai/conversations/{id}
     */
    @PatchMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CopilotConversationSummaryDto> renameConversation(
            @PathVariable Long id,
            @Valid @RequestBody CopilotConversationRenameRequestDto requestDto) {
        CopilotConversationSummaryDto updated = conversationService.renameConversation(id, requestDto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete an owned conversation thread and all its associated requests and messages.
     * DELETE /api/ai/conversations/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteConversation(@PathVariable Long id) {
        conversationService.deleteConversation(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Submit an inquiry to an owned AI Copilot conversation thread.
     * POST /api/ai/conversations/{conversationId}/messages
     */
    @PostMapping("/{conversationId}/messages")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CopilotMessageResponseDto> submitMessage(
            @PathVariable Long conversationId,
            @Valid @RequestBody CopilotSubmissionRequestDto requestDto) {
        CopilotMessageResponseDto response = conversationService.submitMessage(conversationId, requestDto);
        return ResponseEntity.ok(response);
    }
}
