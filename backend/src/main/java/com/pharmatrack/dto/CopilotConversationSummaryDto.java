package com.pharmatrack.dto;

import java.time.LocalDateTime;

/**
 * Summary DTO representing an AI Copilot conversation thread for history listings.
 *
 * <p>Security note: User identity and authorization are strictly resolved server-side.
 * Client-supplied owner fields are not accepted, and internal lease/hash fields are not exposed.</p>
 */
public class CopilotConversationSummaryDto {

    private Long id;
    private String title;
    private String activeModule;
    private String contextType;
    private String contextId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private long messageCount;

    public CopilotConversationSummaryDto() {
    }

    public CopilotConversationSummaryDto(Long id, String title, String activeModule,
                                         String contextType, String contextId,
                                         LocalDateTime createdAt, LocalDateTime updatedAt,
                                         long messageCount) {
        this.id = id;
        this.title = title;
        this.activeModule = activeModule;
        this.contextType = contextType;
        this.contextId = contextId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.messageCount = messageCount;
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

    public long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(long messageCount) {
        this.messageCount = messageCount;
    }

    @Override
    public String toString() {
        return "CopilotConversationSummaryDto{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", activeModule='" + activeModule + '\'' +
                ", contextType='" + contextType + '\'' +
                ", contextId='" + contextId + '\'' +
                ", messageCount=" + messageCount +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
