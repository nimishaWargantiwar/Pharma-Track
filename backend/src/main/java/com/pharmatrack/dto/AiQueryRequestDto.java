package com.pharmatrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for submitting an advisory inquiry to PharmaTrack AI Copilot.
 *
 * <p>Security note: User identity, roles, and authorization authorities are strictly resolved
 * server-side via Spring Security's authenticated context. Caller-supplied identity fields are
 * deliberately omitted to prevent unauthorized privilege escalation or context spoofing.</p>
 */
public class AiQueryRequestDto {

    @NotBlank(message = "Query is mandatory")
    @Size(max = 2000, message = "Query must not exceed 2000 characters")
    private String query;

    @Size(max = 50, message = "Active module must not exceed 50 characters")
    private String activeModule;

    @Size(max = 50, message = "Context type must not exceed 50 characters")
    private String contextType;

    @Size(max = 100, message = "Context identifier must not exceed 100 characters")
    private String contextId;

    public AiQueryRequestDto() {
    }

    public AiQueryRequestDto(String query) {
        this.query = query;
    }

    public AiQueryRequestDto(String query, String activeModule, String contextType, String contextId) {
        this.query = query;
        this.activeModule = activeModule;
        this.contextType = contextType;
        this.contextId = contextId;
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

    @Override
    public String toString() {
        return "AiQueryRequestDto{" +
                "activeModule='" + activeModule + '\'' +
                ", contextType='" + contextType + '\'' +
                ", contextId='" + contextId + '\'' +
                ", queryLength=" + (query != null ? query.length() : 0) +
                '}';
    }
}
