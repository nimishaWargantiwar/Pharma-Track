package com.pharmatrack.entity;

import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity representing a persistent multi-turn AI Copilot conversation thread.
 * Scoped strictly to an authenticated {@link UserAccount}.
 */
@Entity
@Table(name = "copilot_conversations", indexes = {
        @Index(name = "idx_copilot_conv_user_updated", columnList = "user_id, updated_at DESC")
})
public class CopilotConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Conversation owner is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount user;

    @NotBlank(message = "Conversation title is mandatory")
    @Size(max = 150, message = "Conversation title must not exceed 150 characters")
    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Size(max = 50, message = "Active module must not exceed 50 characters")
    @Column(name = "active_module", length = 50)
    private String activeModule;

    @Size(max = 50, message = "Context type must not exceed 50 characters")
    @Column(name = "context_type", length = 50)
    private String contextType;

    @Size(max = 100, message = "Context identifier must not exceed 100 characters")
    @Column(name = "context_id", length = 100)
    private String contextId;

    @NotNull(message = "Creation timestamp is mandatory")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @NotNull(message = "Update timestamp is mandatory")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public CopilotConversation() {
    }

    public CopilotConversation(UserAccount user, String title) {
        this(user, title, null, null, null);
    }

    public CopilotConversation(UserAccount user, String title, String activeModule, String contextType, String contextId) {
        this.user = user;
        this.title = title != null ? title.trim() : null;
        this.activeModule = activeModule != null ? activeModule.trim() : null;
        this.contextType = contextType != null ? contextType.trim() : null;
        this.contextId = contextId != null ? contextId.trim() : null;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.title != null) {
            this.title = this.title.trim();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title != null ? title.trim() : null;
    }

    public String getActiveModule() {
        return activeModule;
    }

    public void setActiveModule(String activeModule) {
        this.activeModule = activeModule != null ? activeModule.trim() : null;
    }

    public String getContextType() {
        return contextType;
    }

    public void setContextType(String contextType) {
        this.contextType = contextType != null ? contextType.trim() : null;
    }

    public String getContextId() {
        return contextId;
    }

    public void setContextId(String contextId) {
        this.contextId = contextId != null ? contextId.trim() : null;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CopilotConversation)) return false;
        CopilotConversation other = (CopilotConversation) o;
        if (getId() == null || other.getId() == null) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return CopilotConversation.class.hashCode();
    }

    @Override
    public String toString() {
        return "CopilotConversation{" +
                "id=" + id +
                ", userId=" + (user != null ? user.getId() : null) +
                ", title='" + title + '\'' +
                ", activeModule='" + activeModule + '\'' +
                ", contextType='" + contextType + '\'' +
                ", contextId='" + contextId + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
