package com.pharmatrack.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Detailed DTO representing a complete AI Copilot conversation thread and its chronological messages.
 */
public class CopilotConversationDetailDto {

    private Long id;
    private String title;
    private String activeModule;
    private String contextType;
    private String contextId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<CopilotMessageDto> messages = new ArrayList<>();

    public CopilotConversationDetailDto() {
    }

    public CopilotConversationDetailDto(Long id, String title, String activeModule,
                                       String contextType, String contextId,
                                       LocalDateTime createdAt, LocalDateTime updatedAt,
                                       List<CopilotMessageDto> messages) {
        this.id = id;
        this.title = title;
        this.activeModule = activeModule;
        this.contextType = contextType;
        this.contextId = contextId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        if (messages != null) {
            this.messages = messages;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<CopilotMessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<CopilotMessageDto> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "CopilotConversationDetailDto{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", activeModule='" + activeModule + '\'' +
                ", contextType='" + contextType + '\'' +
                ", contextId='" + contextId + '\'' +
                ", messageCount=" + (messages != null ? messages.size() : 0) +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
