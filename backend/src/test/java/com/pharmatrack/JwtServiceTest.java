package com.pharmatrack;

import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.security.JwtProperties;
import com.pharmatrack.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JwtService} covering fail-closed secret validation,
 * HS256 token issuance, claim extraction, signature tampering, and expiration handling.
 */
class JwtServiceTest {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static String randomRawSecret(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }

    private static String randomBase64Secret(int rawBytes) {
        byte[] bytes = new byte[rawBytes];
        SECURE_RANDOM.nextBytes(bytes);
        return "base64:" + Base64.getEncoder().encodeToString(bytes);
    }

    private static UserAccount sampleUser() {
        UserAccount user = new UserAccount("qa_lead", "$2a$10$hashedvalueplaceholder", "Dr. Rao", Role.QA_REVIEWER, true);
        user.setId(42L);
        user.setTokenVersion(3L);
        return user;
    }

    @Test
    @DisplayName("JwtService fails closed when signing secret is missing, blank, invalid base64, or shorter than 256 bits")
    void rejectsMissingOrWeakSecrets() {
        JwtProperties missingProps = new JwtProperties();
        assertThrows(IllegalStateException.class, () -> new JwtService(missingProps));

        JwtProperties blankProps = new JwtProperties();
        blankProps.setSecret("   ");
        assertThrows(IllegalStateException.class, () -> new JwtService(blankProps));

        JwtProperties shortProps = new JwtProperties();
        shortProps.setSecret(randomRawSecret(31));
        assertThrows(IllegalStateException.class, () -> new JwtService(shortProps));

        JwtProperties shortBase64Props = new JwtProperties();
        shortBase64Props.setSecret(randomBase64Secret(31));
        assertThrows(IllegalStateException.class, () -> new JwtService(shortBase64Props));

        JwtProperties invalidBase64Props = new JwtProperties();
        invalidBase64Props.setSecret("base64:%%%not-valid-base64%%%");
        assertThrows(IllegalStateException.class, () -> new JwtService(invalidBase64Props));

        JwtProperties invalidExpirationProps = new JwtProperties();
        invalidExpirationProps.setSecret(randomRawSecret(48));
        invalidExpirationProps.setExpirationMs(0L);
        assertThrows(IllegalStateException.class, () -> new JwtService(invalidExpirationProps));
    }

    @Test
    @DisplayName("Generates valid HS256 JWT with required claims and verifies signature cleanly")
    void generatesAndValidatesTokenClaims() {
        Instant fixedNow = Instant.parse("2026-11-10T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);

        JwtProperties props = new JwtProperties();
        props.setSecret(randomRawSecret(48));
        props.setExpirationMs(3_600_000L);

        JwtService jwtService = new JwtService(props, fixedClock);
        UserAccount user = sampleUser();

        String token = jwtService.generateToken(user);
        assertNotNull(token);
        assertEquals(3, token.split("\\.").length);

        JwtService.ParsedJwtToken parsed = jwtService.parseAndValidate(token);
        assertEquals("qa_lead", parsed.username());
        assertEquals(42L, parsed.userId());
        assertEquals(Role.QA_REVIEWER, parsed.role());
        assertEquals("Dr. Rao", parsed.fullName());
        assertEquals(3L, parsed.tokenVersion());
        assertEquals(fixedNow, parsed.issuedAt());
        assertEquals(fixedNow.plus(Duration.ofHours(1)), parsed.expiresAt());
    }

    @Test
    @DisplayName("Supports base64: encoded 256-bit secrets")
    void supportsBase64Secret() {
        JwtProperties props = new JwtProperties();
        props.setSecret(randomBase64Secret(32));
        props.setExpirationMs(1_800_000L);

        JwtService jwtService = new JwtService(props);
        String token = jwtService.generateToken(sampleUser());
        JwtService.ParsedJwtToken parsed = jwtService.parseAndValidate(token);
        assertEquals("qa_lead", parsed.username());
        assertEquals(1800L, jwtService.getExpirationSeconds());
    }

    @Test
    @DisplayName("Rejects expired tokens, wrong-key signatures, tampered payloads, alg=none, and missing required claims")
    void rejectsInvalidExpiredAndTamperedTokens() {
        Instant issueTime = Instant.parse("2026-11-10T10:00:00Z");
        String secret = randomRawSecret(48);

        JwtProperties props = new JwtProperties();
        props.setSecret(secret);
        props.setExpirationMs(60_000L);

        JwtService issuingService = new JwtService(props, Clock.fixed(issueTime, ZoneOffset.UTC));
        String token = issuingService.generateToken(sampleUser());

        // Expired clock (+61s)
        JwtService expiredClockService = new JwtService(
                props,
                Clock.fixed(issueTime.plusSeconds(61), ZoneOffset.UTC)
        );
        assertThrows(BadCredentialsException.class, () -> expiredClockService.parseAndValidate(token));

        // Different signing secret
        JwtProperties otherProps = new JwtProperties();
        otherProps.setSecret(randomRawSecret(48));
        JwtService otherSecretService = new JwtService(otherProps, Clock.fixed(issueTime, ZoneOffset.UTC));
        assertThrows(BadCredentialsException.class, () -> otherSecretService.parseAndValidate(token));

        // Tampered signature / payload
        String[] parts = token.split("\\.");
        String tamperedToken = parts[0] + "." + parts[1] + "x." + parts[2];
        assertThrows(BadCredentialsException.class, () -> issuingService.parseAndValidate(tamperedToken));

        // Unsigned alg=none token
        String headerJson = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(BadCredentialsException.class,
                () -> issuingService.parseAndValidate(headerJson + "." + parts[1] + "."));

        // Signed with HS384 instead of HS256
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        String hs384Token = Jwts.builder()
                .subject("qa_lead")
                .claim("uid", 42L)
                .claim("role", "QA_REVIEWER")
                .claim("name", "Dr. Rao")
                .claim("ver", 3L)
                .issuedAt(Date.from(issueTime))
                .expiration(Date.from(issueTime.plusSeconds(300)))
                .signWith(key, Jwts.SIG.HS384)
                .compact();
        assertThrows(BadCredentialsException.class, () -> issuingService.parseAndValidate(hs384Token));

        // Missing tokenVersion claim
        String missingVersionToken = Jwts.builder()
                .subject("qa_lead")
                .claim("uid", 42L)
                .claim("role", "QA_REVIEWER")
                .claim("name", "Dr. Rao")
                .issuedAt(Date.from(issueTime))
                .expiration(Date.from(issueTime.plusSeconds(300)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        assertThrows(BadCredentialsException.class, () -> issuingService.parseAndValidate(missingVersionToken));
    }
}

