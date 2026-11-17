package com.pharmatrack.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for submitting an inquiry to a persistent AI Copilot conversation.
 *
 * <p>Enforces unique client-generated request identification for idempotency, input bounds,
 * and strict server-side identity resolution.</p>
 */
public class CopilotSubmissionRequestDto {

    @NotBlank(message = "Client request ID is mandatory")
    @Size(max = 64, message = "Client request ID must not exceed 64 characters")
    private String clientRequestId;

    @NotBlank(message = "Query is mandatory")
    @Size(max = 2000, message = "Query must not exceed 2000 characters")
    private String query;

    @Size(max = 50, message = "Active module must not exceed 50 characters")
    private String activeModule;

    @Size(max = 50, message = "Context type must not exceed 50 characters")
    private String contextType;

    @Size(max = 100, message = "Context identifier must not exceed 100 characters")
    private String contextId;

    public CopilotSubmissionRequestDto() {
    }

    public CopilotSubmissionRequestDto(String clientRequestId, String query) {
        this(clientRequestId, query, null, null, null);
    }

    public CopilotSubmissionRequestDto(String clientRequestId, String query, String activeModule,
                                      String contextType, String contextId) {
        this.clientRequestId = clientRequestId;
        this.query = query;
        this.activeModule = activeModule;
        this.contextType = contextType;
        this.contextId = contextId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getActiveModule() {
        return activeModule;
    }

    public void setActiveModule(String activeModule) {
        this.activeModule = activeModule;
    }

    public String getContextType() {
        return contextType;
    }

    public void setContextType(String contextType) {
        this.contextType = contextType;
    }

    public String getContextId() {
        return contextId;
    }

    public void setContextId(String contextId) {
        this.contextId = contextId;
    }

    @JsonAnySetter
    public void handleUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unrecognized field: " + name);
    }

    @Override
    public String toString() {
        return "CopilotSubmissionRequestDto{" +
                "clientRequestId='" + clientRequestId + '\'' +
                ", activeModule='" + activeModule + '\'' +
                ", contextType='" + contextType + '\'' +
                ", contextId='" + contextId + '\'' +
                ", queryLength=" + (query != null ? query.length() : 0) +
                '}';
    }
}
