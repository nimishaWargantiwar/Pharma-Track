package com.pharmatrack.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload DTO for renaming an AI Copilot conversation thread.
 */
public class CopilotConversationRenameRequestDto {

    @NotBlank(message = "Conversation title is mandatory")
    @Size(max = 150, message = "Conversation title must not exceed 150 characters")
    private String title;

    public CopilotConversationRenameRequestDto() {
    }

    public CopilotConversationRenameRequestDto(String title) {
        this.title = title;
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
}
