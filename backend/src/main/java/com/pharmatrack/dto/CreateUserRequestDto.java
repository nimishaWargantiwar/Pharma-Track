package com.pharmatrack.dto;

import com.pharmatrack.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Administrator request payload for creating a new user account.
 */
public class CreateUserRequestDto {

    @Null(message = "User ID must not be provided when creating a user")
    private Long id;

    @NotBlank(message = "Username is mandatory")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Pattern(
            regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,49}$",
            message = "Username must start with an alphanumeric character and contain only letters, digits, dots, underscores, or hyphens"
    )
    private String username;

    @NotBlank(message = "Password is mandatory")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

    @NotBlank(message = "Full name is mandatory")
    @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
    private String fullName;

    @NotNull(message = "Role is mandatory")
    private Role role;

    private Boolean enabled;

    public CreateUserRequestDto() {
    }

    public CreateUserRequestDto(String username, String password, String fullName, Role role) {
        this(username, password, fullName, role, Boolean.TRUE);
    }

    public CreateUserRequestDto(String username, String password, String fullName, Role role, Boolean enabled) {
        this.username = username;
        this.password = password;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
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
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}

