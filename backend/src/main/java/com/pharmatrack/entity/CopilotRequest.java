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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity tracking the idempotent execution, lease, and lifecycle of a user inquiry submission.
 */
@Entity
@Table(name = "copilot_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_copilot_req_client", columnNames = {"conversation_id", "client_request_id"})
}, indexes = {
        @Index(name = "idx_copilot_req_conv_started", columnList = "conversation_id, started_at"),
        @Index(name = "idx_copilot_req_status_expires", columnList = "status, lease_expires_at")
})
public class CopilotRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotNull(message = "Parent conversation is mandatory")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CopilotConversation conversation;

    @NotBlank(message = "Client request ID is mandatory")
    @Size(max = 64, message = "Client request ID must not exceed 64 characters")
    @Column(name = "client_request_id", nullable = false, updatable = false, length = 64)
    private String clientRequestId;

    @NotBlank(message = "Request hash is mandatory")
    @Size(max = 64, message = "Request hash must not exceed 64 characters")
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @NotNull(message = "Request status is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CopilotRequestStatus status;

    @Size(max = 64, message = "Lease token must not exceed 64 characters")
    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @NotNull(message = "Start timestamp is mandatory")
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Size(max = 500, message = "Error message must not exceed 500 characters")
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    public CopilotRequest() {
    }

    public CopilotRequest(CopilotConversation conversation, String clientRequestId, String requestHash) {
        this(conversation, clientRequestId, requestHash, CopilotRequestStatus.PENDING, null, null);
    }

    public CopilotRequest(CopilotConversation conversation, String clientRequestId, String requestHash,
                          CopilotRequestStatus status, String leaseToken, LocalDateTime leaseExpiresAt) {
        this.conversation = conversation;
        this.clientRequestId = clientRequestId != null ? clientRequestId.trim() : null;
        this.requestHash = requestHash != null ? requestHash.trim() : null;
        this.status = status != null ? status : CopilotRequestStatus.PENDING;
        this.leaseToken = leaseToken != null ? leaseToken.trim() : null;
        this.leaseExpiresAt = leaseExpiresAt;
    }

    @PrePersist
    protected void onCreate() {
        if (this.startedAt == null) {
            this.startedAt = AppTime.nowDateTime();
        }
        if (this.status == null) {
            this.status = CopilotRequestStatus.PENDING;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CopilotConversation getConversation() {
        return conversation;
    }

    public void setConversation(CopilotConversation conversation) {
        this.conversation = conversation;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId != null ? clientRequestId.trim() : null;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash != null ? requestHash.trim() : null;
    }

    public CopilotRequestStatus getStatus() {
        return status;
    }

    public void setStatus(CopilotRequestStatus status) {
        this.status = status;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String leaseToken) {
        this.leaseToken = leaseToken != null ? leaseToken.trim() : null;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage != null ? errorMessage.trim() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CopilotRequest)) return false;
        CopilotRequest other = (CopilotRequest) o;
        if (getId() == null || other.getId() == null) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return CopilotRequest.class.hashCode();
    }

    @Override
    public String toString() {
        return "CopilotRequest{" +
                "id=" + id +
                ", conversationId=" + (conversation != null ? conversation.getId() : null) +
                ", clientRequestId='" + clientRequestId + '\'' +
                ", status=" + status +
                ", startedAt=" + startedAt +
                ", leaseExpiresAt=" + leaseExpiresAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
