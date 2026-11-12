package com.pharmatrack.security;

import com.pharmatrack.entity.UserAccount;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Resolves the trusted actor identity and roles strictly from Spring Security's
 * {@link SecurityContextHolder}. Fails closed when no authenticated non-anonymous
 * principal is present so caller-supplied headers or request body fields can never
 * override audit attribution.
 */
@Component
public class AuthenticatedActorResolver {

    private static final int MAX_ACTOR_LENGTH = 100;

    /**
     * Resolve the normalized lowercase username of the currently authenticated user.
     *
     * @return non-blank normalized username bounded to 100 characters
     * @throws AuthenticationCredentialsNotFoundException if no authenticated principal exists
     * @throws InsufficientAuthenticationException          if the principal identity is blank, anonymous, or oversized
     */
    public String requireCurrentActor() {
        Authentication authentication = requireAuthenticatedContext();

        String rawUsername;
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails customUserDetails) {
            rawUsername = customUserDetails.getUsername();
        } else if (principal instanceof UserDetails userDetails) {
            rawUsername = userDetails.getUsername();
        } else {
            rawUsername = authentication.getName();
        }

        String normalized = UserAccount.normalizeUsername(rawUsername);
        if (normalized == null || normalized.isEmpty() || "anonymoususer".equals(normalized)) {
            throw new InsufficientAuthenticationException("Authenticated user identity is unavailable");
        }
        if (normalized.length() > MAX_ACTOR_LENGTH) {
            throw new InsufficientAuthenticationException("Authenticated username exceeds maximum audit actor length");
        }
        return normalized;
    }

    /**
     * Check whether the currently authenticated principal holds at least one of the specified role names.
     *
     * @param roles unprefixed (e.g. {@code ADMIN}) or {@code ROLE_}-prefixed role names
     * @return true if the authenticated context grants any matching authority
     */
    public boolean hasAnyRole(String... roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isValidAuthenticated(authentication) || roles == null || roles.length == 0) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String expectedAuthority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
            for (GrantedAuthority authority : authorities) {
                if (authority != null && expectedAuthority.equals(authority.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Authentication requireAuthenticatedContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isValidAuthenticated(authentication)) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated user context is required");
        }
        return authentication;
    }

    private boolean isValidAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}

