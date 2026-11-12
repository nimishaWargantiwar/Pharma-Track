package com.pharmatrack.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Immutable Spring Security principal backed by {@link UserAccount}.
 */
public class CustomUserDetails implements UserDetails {

    private final Long userId;
    private final String username;
    @JsonIgnore
    private final String passwordHash;
    private final String fullName;
    private final Role role;
    private final boolean enabled;
    private final long tokenVersion;
    private final List<GrantedAuthority> authorities;

    public CustomUserDetails(Long userId,
                             String username,
                             String passwordHash,
                             String fullName,
                             Role role,
                             boolean enabled,
                             long tokenVersion) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
        this.tokenVersion = tokenVersion;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    public static CustomUserDetails fromEntity(UserAccount user) {
        return new CustomUserDetails(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getFullName(),
                user.getRole(),
                user.isEnabled(),
                user.getTokenVersion()
        );
    }

    public Long getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

