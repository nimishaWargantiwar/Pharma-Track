package com.pharmatrack.service;

import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.dto.LoginResponseDto;
import com.pharmatrack.dto.UserSummaryDto;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.CustomUserDetails;
import com.pharmatrack.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service handling stateless user credential authentication and current-user identity resolution.
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String dummyPasswordHash;

    public AuthService(UserAccountRepository userAccountRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode("pharmatrack-timing-protection-sentinel");
    }

    public LoginResponseDto login(LoginRequestDto request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new BadCredentialsException("Invalid username or password");
        }

        String normalizedUsername = UserAccount.normalizeUsername(request.getUsername());
        if (normalizedUsername == null || normalizedUsername.isEmpty()) {
            passwordEncoder.matches(request.getPassword(), dummyPasswordHash);
            throw new BadCredentialsException("Invalid username or password");
        }

        UserAccount user = userAccountRepository.findByUsernameIgnoreCase(normalizedUsername).orElse(null);
        String candidateHash = user != null ? user.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), candidateHash);

        if (user == null || !passwordMatches) {
            throw new BadCredentialsException("Invalid username or password");
        }

        if (!user.isEnabled()) {
            throw new DisabledException("User account is disabled");
        }

        String token = jwtService.generateToken(user);
        return new LoginResponseDto(
                token,
                "Bearer",
                jwtService.getExpirationSeconds(),
                UserSummaryDto.fromEntity(user)
        );
    }

    public UserSummaryDto getCurrentUser(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new InsufficientAuthenticationException("Authentication is required");
        }

        UserAccount user;
        if (authentication.getPrincipal() instanceof CustomUserDetails details) {
            user = userAccountRepository.findById(details.getUserId())
                    .orElseThrow(() -> new BadCredentialsException("User account is unavailable"));
        } else {
            String normalizedUsername = UserAccount.normalizeUsername(authentication.getName());
            user = userAccountRepository.findByUsernameIgnoreCase(normalizedUsername)
                    .orElseThrow(() -> new BadCredentialsException("User account is unavailable"));
        }

        if (!user.isEnabled()) {
            throw new DisabledException("User account is disabled");
        }

        return UserSummaryDto.fromEntity(user);
    }
}

