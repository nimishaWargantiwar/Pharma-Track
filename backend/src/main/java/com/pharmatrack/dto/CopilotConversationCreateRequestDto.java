package com.pharmatrack.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for creating a new persistent AI Copilot conversation thread.
 *
 * <p>Strictly validates title length and rejects unknown JSON properties to prevent
 * unauthorized identity injection or parameter tampering.</p>
 */
public class CopilotConversationCreateRequestDto {

    @NotBlank(message = "Conversation title is mandatory")
    @Size(max = 150, message = "Conversation title must not exceed 150 characters")
    private String title;

    @Size(max = 50, message = "Active module must not exceed 50 characters")
    private String activeModule;

    @Size(max = 50, message = "Context type must not exceed 50 characters")
    private String contextType;

    @Size(max = 100, message = "Context identifier must not exceed 100 characters")
    private String contextId;

    public CopilotConversationCreateRequestDto() {
    }

    public CopilotConversationCreateRequestDto(String title) {
        this(title, null, null, null);
    }

    public CopilotConversationCreateRequestDto(String title, String activeModule, String contextType, String contextId) {
        this.title = title;
        this.activeModule = activeModule;
        this.contextType = contextType;
        this.contextId = contextId;
    }

    @JsonAnySetter
    public void handleUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unrecognized field: " + name);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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
}
