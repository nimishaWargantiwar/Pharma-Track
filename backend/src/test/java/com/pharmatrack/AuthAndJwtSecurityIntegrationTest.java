package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.ProductRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtProperties;
import com.pharmatrack.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full Spring Boot + H2 + MockMvc integration tests covering stateless JWT authentication,
 * login/me contracts, BCrypt hashing, signature/expiration/revocation/disabled checks,
 * CORS preflight, deny-by-default unmapped routes, public health, and protected business routes.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_auth_jwt_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthAndJwtSecurityIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    private static String ephemeralStrongPassword() {
        return "TestPass9" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private UserAccount createPersistedUser(String prefix, String rawPassword, Role role, boolean enabled) {
        int n = SEQ.getAndIncrement();
        UserAccount user = new UserAccount(
                prefix + "." + n,
                passwordEncoder.encode(rawPassword),
                "Test User " + n,
                role,
                enabled
        );
        return userAccountRepository.saveAndFlush(user);
    }

    private String loginAndExtractToken(String username, String rawPassword) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(username, rawPassword))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("1. Login verifies BCrypt password case-insensitively on username, issues JWT without passwordHash, and powers /api/auth/me")
    void loginSuccessAndCurrentUserProfile() throws Exception {
        String rawPassword = ephemeralStrongPassword();
        UserAccount user = createPersistedUser("AuthOp", rawPassword, Role.OPERATOR, true);

        assertNotEquals(rawPassword, user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertTrue(passwordEncoder.matches(rawPassword, user.getPasswordHash()));

        // Login using mixed-case + outer whitespace to verify normalized lookup
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequestDto("  " + user.getUsername().toUpperCase() + " ", rawPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.user.id").value(user.getId()))
                .andExpect(jsonPath("$.user.username").value(user.getUsername()))
                .andExpect(jsonPath("$.user.fullName").value(user.getFullName()))
                .andExpect(jsonPath("$.user.role").value("OPERATOR"))
                .andExpect(jsonPath("$.user.enabled").value(true))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString(user.getPasswordHash()))))
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginJson.get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.username").value(user.getUsername()))
                .andExpect(jsonPath("$.fullName").value(user.getFullName()))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    @DisplayName("2. Invalid credentials, unknown user, disabled user, and validation errors behave consistently without account enumeration")
    void loginRejectionsAndValidation() throws Exception {
        String rawPassword = ephemeralStrongPassword();
        UserAccount enabledUser = createPersistedUser("valid", rawPassword, Role.QC_ANALYST, true);

        String disabledPassword = ephemeralStrongPassword();
        UserAccount disabledUser = createPersistedUser("disabled", disabledPassword, Role.OPERATOR, false);

        // Wrong password -> 401
        MvcResult wrongPasswordResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequestDto(enabledUser.getUsername(), rawPassword + "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andReturn();

        // Unknown username -> 401 with identical non-enumerating message
        MvcResult unknownUserResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequestDto("nonexistent.user.xyz", rawPassword))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andReturn();

        assertEquals(
                objectMapper.readTree(wrongPasswordResult.getResponse().getContentAsString()).get("message").asText(),
                objectMapper.readTree(unknownUserResult.getResponse().getContentAsString()).get("message").asText()
        );

        // Disabled user with wrong password -> non-enumerating 401
        MvcResult disabledWrongPwd = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequestDto(disabledUser.getUsername(), "WrongPwd999"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertEquals(
                objectMapper.readTree(unknownUserResult.getResponse().getContentAsString()).get("message").asText(),
                objectMapper.readTree(disabledWrongPwd.getResponse().getContentAsString()).get("message").asText()
        );

        // Disabled user with right password -> 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequestDto(disabledUser.getUsername(), disabledPassword))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Blank credentials -> 400 validation error
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));

        // Login succeeds even if caller accidentally attaches stale invalid Bearer header
        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.stale.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequestDto(enabledUser.getUsername(), rawPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("3. /api/auth/me rejects anonymous, Basic auth, tampered/wrong-signature JWT, and expired JWT with JSON 401")
    void authMeRejectsAnonymousAndInvalidTokens() throws Exception {
        String rawPassword = ephemeralStrongPassword();
        UserAccount user = createPersistedUser("tokencheck", rawPassword, Role.PRODUCTION_SUPERVISOR, true);
        String validToken = loginAndExtractToken(user.getUsername(), rawPassword);

        // Anonymous
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        // Basic Auth disabled
        String basicCreds = Base64.getEncoder().encodeToString((user.getUsername() + ":" + rawPassword).getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + basicCreds))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Malformed / empty Bearer
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized());

        // Tampered token
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken + "tamper"))
                .andExpect(status().isUnauthorized());

        // Expired token signed with the active test key
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(120);
        String expiredToken = Jwts.builder()
                .subject(user.getUsername())
                .claim(JwtService.CLAIM_USER_ID, user.getId())
                .claim(JwtService.CLAIM_ROLE, user.getRole().name())
                .claim(JwtService.CLAIM_FULL_NAME, user.getFullName())
                .claim(JwtService.CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("4. Disabling user or incrementing tokenVersion immediately invalidates previously issued JWTs on subsequent requests")
    void liveDbEnabledAndTokenVersionRevocationEnforcedPerRequest() throws Exception {
        String rawPassword = ephemeralStrongPassword();
        UserAccount user = createPersistedUser("revoke", rawPassword, Role.QA_REVIEWER, true);
        String originalToken = loginAndExtractToken(user.getUsername(), rawPassword);

        // Sanity check valid token
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + originalToken))
                .andExpect(status().isOk());

        // Disable user in DB -> previously issued token immediately fails 401
        user.setEnabled(false);
        userAccountRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + originalToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // Re-enable user AND bump tokenVersion -> pre-revocation token still rejected (401), new login token works (200)
        user.setEnabled(true);
        user.incrementTokenVersion();
        userAccountRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + originalToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        String freshToken = loginAndExtractToken(user.getUsername(), rawPassword);
        assertNotEquals(originalToken, freshToken);
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + freshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(user.getUsername()));
    }

    @Test
    @DisplayName("5. Health check is public, CORS allows configured frontend origins + Authorization, unmapped routes deny by default, and business routes require JWT + RBAC")
    void publicHealthCorsDenyByDefaultAndProtectedBusinessRoutes() throws Exception {
        // Public health check works anonymously and even with stale bearer header
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/api/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer stale.bad.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // CORS preflight from allowed localhost:5173 and 127.0.0.1:5173 origins
        mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")));

        mockMvc.perform(options("/api/admin/users")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:5173"));

        // CORS rejects unknown origin (no allow-origin header echoed)
        mockMvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        // Deny-by-default on unmapped routes & no public self-registration
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/unmapped/resource"))
                .andExpect(status().isUnauthorized());

        String adminPwd = ephemeralStrongPassword();
        UserAccount admin = createPersistedUser("corsadmin", adminPwd, Role.ADMIN, true);
        String adminToken = loginAndExtractToken(admin.getUsername(), adminPwd);

        mockMvc.perform(get("/api/unmapped/resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        // Phase 6B-3: business endpoints reject anonymous requests with 401 and require valid JWT + RBAC
        long countBefore = productRepository.count();
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized());

        ProductCreateRequestDto dto = new ProductCreateRequestDto(
                "AUTH-PROT-" + SEQ.getAndIncrement(),
                "Protected Product",
                "500 mg",
                "Tablet",
                "Phase 6B-3 authentication check",
                ProductStatus.ACTIVE
        );
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
        assertEquals(countBefore, productRepository.count());

        // Authorized ADMIN JWT succeeds on /api/products
        mockMvc.perform(get("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());
        assertEquals(countBefore + 1, productRepository.count());

        // Invalid Bearer token sent to protected route is rejected with 401
        mockMvc.perform(get("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tampered.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("6. Returns stable, non-sensitive error codes in ErrorResponse (TOKEN_EXPIRED, TOKEN_INVALID, TOKEN_REVOKED, ACCOUNT_DISABLED, AUTHENTICATION_REQUIRED)")
    void jwtErrorCodeClassificationEnforced() throws Exception {
        String rawPassword = ephemeralStrongPassword();
        UserAccount user = createPersistedUser("errorcode", rawPassword, Role.OPERATOR, true);
        String validToken = loginAndExtractToken(user.getUsername(), rawPassword);

        // AUTHENTICATION_REQUIRED: No token
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));

        // AUTHENTICATION_REQUIRED: Empty Bearer token
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));

        // TOKEN_INVALID: Malformed / tampered token
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken + "tamper"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_INVALID"));

        // TOKEN_EXPIRED: Expired token
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(120);
        String expiredToken = Jwts.builder()
                .subject(user.getUsername())
                .claim(JwtService.CLAIM_USER_ID, user.getId())
                .claim(JwtService.CLAIM_ROLE, user.getRole().name())
                .claim(JwtService.CLAIM_FULL_NAME, user.getFullName())
                .claim(JwtService.CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));

        // TOKEN_REVOKED: tokenVersion incremented
        user.incrementTokenVersion();
        userAccountRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_REVOKED"));

        // ACCOUNT_DISABLED: user disabled
        String activePassword = ephemeralStrongPassword();
        UserAccount disabledUser = createPersistedUser("willdisable", activePassword, Role.OPERATOR, true);
        String disabledUserToken = loginAndExtractToken(disabledUser.getUsername(), activePassword);
        disabledUser.setEnabled(false);
        userAccountRepository.saveAndFlush(disabledUser);

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + disabledUserToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DISABLED"));
    }
}

