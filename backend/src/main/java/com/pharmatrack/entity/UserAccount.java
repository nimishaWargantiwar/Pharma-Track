package com.pharmatrack.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pharmatrack.util.AppTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

/**
 * Persistent user account for stateless JWT authentication and RBAC authorization.
 */
@Entity
@Table(name = "user_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_accounts_username", columnNames = "username")
}, indexes = {
        @Index(name = "idx_user_accounts_role", columnList = "role")
})
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @NotBlank(message = "Username is mandatory")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @JsonIgnore
    @NotBlank(message = "Password hash is mandatory")
    @Size(max = 120, message = "Password hash must not exceed 120 characters")
    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @NotBlank(message = "Full name is mandatory")
    @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @NotNull(message = "Role is mandatory")
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public UserAccount() {
    }

    public UserAccount(String username, String passwordHash, String fullName, Role role, boolean enabled) {
        this.username = normalizeUsername(username);
        this.passwordHash = passwordHash;
        this.fullName = fullName != null ? fullName.trim() : null;
        this.role = role;
        this.enabled = enabled;
        this.tokenVersion = 0L;
    }

    public static String normalizeUsername(String rawUsername) {
        return rawUsername == null ? null : rawUsername.trim().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = AppTime.nowDateTime();
        this.createdAt = now;
        this.updatedAt = now;
        this.username = normalizeUsername(this.username);
        if (this.fullName != null) {
            this.fullName = this.fullName.trim();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = AppTime.nowDateTime();
        this.username = normalizeUsername(this.username);
        if (this.fullName != null) {
            this.fullName = this.fullName.trim();
        }
    }

    public void incrementTokenVersion() {
        this.tokenVersion = this.tokenVersion + 1L;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = normalizeUsername(username);
    }

    @JsonIgnore
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName != null ? fullName.trim() : null;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(long tokenVersion) {
        this.tokenVersion = tokenVersion;
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
        if (!(o instanceof UserAccount)) return false;
        UserAccount other = (UserAccount) o;
        if (getId() == null || other.getId() == null) {
            return false;
        }
        return Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return UserAccount.class.hashCode();
    }

    @Override
    public String toString() {
        return "UserAccount{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", fullName='" + fullName + '\'' +
                ", role=" + role +
                ", enabled=" + enabled +
                ", tokenVersion=" + tokenVersion +
                '}';
    }
}

