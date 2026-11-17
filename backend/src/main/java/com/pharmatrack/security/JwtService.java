package com.pharmatrack.security;

import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Stateless HS256 JWT issuance and cryptographic validation service.
 * Fails closed at startup if the configured signing secret is missing or weaker than 256 bits.
 */
@Service
public class JwtService {

    public static final int MIN_SECRET_BYTES = 32;
    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_FULL_NAME = "name";
    public static final String CLAIM_TOKEN_VERSION = "ver";

    private static final String BASE64_PREFIX = "base64:";

    private final SecretKey signingKey;
    private final long expirationMs;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public JwtService(JwtProperties properties, Clock clock) {
        if (properties == null) {
            throw new IllegalStateException("JWT properties must not be null");
        }
        this.expirationMs = properties.getExpirationMs();
        if (this.expirationMs <= 0L) {
            throw new IllegalStateException("app.jwt.expiration-ms must be greater than 0");
        }
        this.clock = clock != null ? clock : Clock.systemUTC();
        byte[] keyBytes = resolveSecretKeyBytes(properties.getSecret());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    private static byte[] resolveSecretKeyBytes(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalStateException(
                    "Missing required JWT signing secret (app.jwt.secret / JWT_SECRET). Configure a secret of at least 256 bits (32 bytes).");
        }

        byte[] keyBytes;
        if (rawSecret.startsWith(BASE64_PREFIX)) {
            String encoded = rawSecret.substring(BASE64_PREFIX.length()).trim();
            if (encoded.isEmpty()) {
                throw new IllegalStateException("Base64 JWT signing secret is empty");
            }
            try {
                keyBytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Invalid Base64 value configured for app.jwt.secret", ex);
            }
        } else {
            keyBytes = rawSecret.getBytes(StandardCharsets.UTF_8);
        }

        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT signing secret is too weak (" + keyBytes.length + " bytes). Minimum required is " + MIN_SECRET_BYTES + " bytes (256 bits).");
        }
        return keyBytes;
    }

    public long getExpirationSeconds() {
        return Math.max(1L, expirationMs / 1000L);
    }

    public String generateToken(UserAccount user) {
        if (user == null || user.getId() == null || user.getUsername() == null || user.getRole() == null) {
            throw new IllegalArgumentException("Persisted user with id, username, and role is required to issue a JWT");
        }

        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusMillis(expirationMs);

        return Jwts.builder()
                .subject(UserAccount.normalizeUsername(user.getUsername()))
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_FULL_NAME, user.getFullName())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public ParsedJwtToken parseAndValidate(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtAuthenticationException(JwtAuthenticationException.CODE_AUTHENTICATION_REQUIRED, "Authentication token is missing");
        }

        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token.trim());

            String algorithm = jws.getHeader().getAlgorithm();
            if (!Jwts.SIG.HS256.getId().equals(algorithm)) {
                throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "Unsupported JWT signature algorithm");
            }

            Claims claims = jws.getPayload();
            String username = UserAccount.normalizeUsername(claims.getSubject());
            if (username == null || username.isEmpty()) {
                throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "JWT token is missing a valid subject");
            }

            Long userId = extractLongClaim(claims, CLAIM_USER_ID);
            if (userId == null || userId <= 0L) {
                throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "JWT token is missing a valid user ID claim");
            }

            String roleValue = claims.get(CLAIM_ROLE, String.class);
            if (roleValue == null || roleValue.isBlank()) {
                throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "JWT token is missing a role claim");
            }
            Role role;
            try {
                role = Role.valueOf(roleValue.trim());
            } catch (IllegalArgumentException ex) {
                throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "JWT token contains an unknown role");
            }

            String fullName = claims.get(CLAIM_FULL_NAME, String.class);
            if (fullName == null || fullName.isBlank()) {
                throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "JWT token is missing a valid name claim");
            }

            Long tokenVersion = extractLongClaim(claims, CLAIM_TOKEN_VERSION);
            if (tokenVersion == null || tokenVersion < 0L) {
                throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "JWT token is missing a valid token version claim");
            }

            Date issuedAt = claims.getIssuedAt();
            Date expiration = claims.getExpiration();
            if (issuedAt == null || expiration == null) {
                throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "JWT token is missing required timestamp claims");
            }

            return new ParsedJwtToken(
                    username,
                    userId,
                    role,
                    fullName.trim(),
                    tokenVersion,
                    issuedAt.toInstant(),
                    expiration.toInstant()
            );
        } catch (JwtAuthenticationException ex) {
            throw ex;
        } catch (ExpiredJwtException ex) {
            throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_EXPIRED, "Authentication token has expired", ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new JwtAuthenticationException(JwtAuthenticationException.CODE_TOKEN_INVALID, "Invalid or expired authentication token", ex);
        }
    }

    private static Long extractLongClaim(Claims claims, String claimName) {
        Object value = claims.get(claimName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public record ParsedJwtToken(
            String username,
            Long userId,
            Role role,
            String fullName,
            long tokenVersion,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }
}

