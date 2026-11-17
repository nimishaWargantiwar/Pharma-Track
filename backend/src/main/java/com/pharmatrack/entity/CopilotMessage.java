package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing an individual grounded message in an AI Copilot conversation.
 * Exactly one USER message and one ASSISTANT response exist per {@link CopilotRequest}.
 */
@Entity
@Table(name = "copilot_messages", uniqueConstraints = {
        @UniqueConstraint(name = "uk_copilot_msg_request_role", columnNames = {"request_id", "role"})
})
public class CopilotMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Parent request is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CopilotRequest request;

    @NotNull(message = "Message role is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false, length = 20)
    private CopilotMessageRole role;

    @NotNull(message = "Message content is mandatory")
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Size(max = 50, message = "Model identifier must not exceed 50 characters")
    @Column(name = "model", length = 50)
    private String model;

    @Size(max = 50, message = "Context type must not exceed 50 characters")
    @Column(name = "context_type", length = 50)
    private String contextType;

    @Size(max = 100, message = "Context identifier must not exceed 100 characters")
    @Column(name = "context_identifier", length = 100)
    private String contextIdentifier;

    @Column(name = "sources_json", columnDefinition = "TEXT")
    private String sourcesJson;

    @NotNull(message = "Creation timestamp is mandatory")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public CopilotMessage() {
    }

    public CopilotMessage(CopilotRequest request, CopilotMessageRole role, String content) {
        this(request, role, content, null, null, null, null);
    }

    public CopilotMessage(CopilotRequest request, CopilotMessageRole role, String content,
                          String model, String contextType, String contextIdentifier, String sourcesJson) {
        this.request = request;
        this.role = role;
        this.content = content != null ? content : "";
        this.model = model != null ? model.trim() : null;
        this.contextType = contextType != null ? contextType.trim() : null;
        this.contextIdentifier = contextIdentifier != null ? contextIdentifier.trim() : null;
        this.sourcesJson = sourcesJson;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = AppTime.nowDateTime();
        }
        if (this.content == null) {
            this.content = "";
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CopilotRequest getRequest() {
        return request;
    }

    public void setRequest(CopilotRequest request) {
        this.request = request;
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
        this.model = model != null ? model.trim() : null;
    }

    public String getContextType() {
        return contextType;
    }

    public void setContextType(String contextType) {
        this.contextType = contextType != null ? contextType.trim() : null;
    }

    public String getContextIdentifier() {
        return contextIdentifier;
    }

    public void setContextIdentifier(String contextIdentifier) {
        this.contextIdentifier = contextIdentifier != null ? contextIdentifier.trim() : null;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CopilotMessage)) return false;
        CopilotMessage other = (CopilotMessage) o;
        if (getId() == null || other.getId() == null) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return CopilotMessage.class.hashCode();
    }

    @Override
    public String toString() {
        return "CopilotMessage{" +
                "id=" + id +
                ", requestId=" + (request != null ? request.getId() : null) +
                ", role=" + role +
                ", model='" + model + '\'' +
                ", contextType='" + contextType + '\'' +
                ", contextIdentifier='" + contextIdentifier + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
