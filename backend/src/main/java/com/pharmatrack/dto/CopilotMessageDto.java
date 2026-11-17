package com.pharmatrack.dto;

import com.pharmatrack.entity.CopilotMessageRole;
import com.pharmatrack.entity.CopilotRequestStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing an individual message turn within an AI Copilot conversation.
 *
 * <p>Never exposes database implementation details, lease tokens, or internal provider reasoning.</p>
 */
public class CopilotMessageDto {

    private Long id;
    private Long requestId;
    private CopilotMessageRole role;
    private String content;
    private String model;
    private String contextType;
    private String contextIdentifier;
    private List<AiSourceReferenceDto> sources = new ArrayList<>();
    private CopilotRequestStatus status;
    private LocalDateTime createdAt;

    public CopilotMessageDto() {
    }

    public CopilotMessageDto(Long id, Long requestId, CopilotMessageRole role, String content,
                             String model, String contextType, String contextIdentifier,
                             List<AiSourceReferenceDto> sources, CopilotRequestStatus status,
                             LocalDateTime createdAt) {
        this.id = id;
        this.requestId = requestId;
        this.role = role;
        this.content = content != null ? content : "";
        this.model = model;
        this.contextType = contextType;
        this.contextIdentifier = contextIdentifier;
        if (sources != null) {
            this.sources = sources;
        }
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public CopilotMessageRole getRole() {
        return role;
    }

    public void setRole(CopilotMessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content != null ? content : "";
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getContextType() {
        return contextType;
    }

    public void setContextType(String contextType) {
        this.contextType = contextType;
    }

    public String getContextIdentifier() {
        return contextIdentifier;
    }

    public void setContextIdentifier(String contextIdentifier) {
        this.contextIdentifier = contextIdentifier;
    }

    public List<AiSourceReferenceDto> getSources() {
        return sources;
    }

    public void setSources(List<AiSourceReferenceDto> sources) {
        this.sources = sources != null ? sources : new ArrayList<>();
    }

    public CopilotRequestStatus getStatus() {
        return status;
    }

    public void setStatus(CopilotRequestStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "CopilotMessageDto{" +
                "id=" + id +
                ", requestId=" + requestId +
                ", role=" + role +
                ", model='" + model + '\'' +
                ", status=" + status +
                ", sourcesCount=" + (sources != null ? sources.size() : 0) +
                ", createdAt=" + createdAt +
                '}';
    }
}
