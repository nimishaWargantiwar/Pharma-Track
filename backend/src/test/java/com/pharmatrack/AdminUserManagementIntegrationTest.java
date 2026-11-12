package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.CreateUserRequestDto;
import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.dto.ResetUserPasswordRequestDto;
import com.pharmatrack.dto.UpdateUserProfileRequestDto;
import com.pharmatrack.dto.UpdateUserRoleRequestDto;
import com.pharmatrack.dto.UpdateUserStatusRequestDto;
import com.pharmatrack.entity.AuditLog;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.service.AdminUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full Spring Boot + H2 + MockMvc integration tests for {@code /api/admin/users/**}
 * verifying strict ADMIN-only access control, BCrypt persistence, validation, duplicate handling,
 * self-disable prevention, last-enabled-admin guardrails, and JWT tokenVersion revocation.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_admin_users_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminUserManagementIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanUsers() {
        SecurityContextHolder.clearContext();
        userAccountRepository.deleteAll();
    }

    private static String ephemeralStrongPassword() {
        return "SecTest7" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private UserAccount createPersistedUser(String prefix, String rawPassword, Role role, boolean enabled) {
        int n = SEQ.getAndIncrement();
        UserAccount user = new UserAccount(
                prefix + "_" + n,
                passwordEncoder.encode(rawPassword),
                "User " + role.name() + " " + n,
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
    @DisplayName("1. Anonymous callers receive 401 and all 4 non-ADMIN roles receive 403 on all /api/admin/users/** routes")
    void enforcesAdminOnlyAuthorizationAcrossAllRoles() throws Exception {
        String targetPwd = ephemeralStrongPassword();
        UserAccount target = createPersistedUser("target", targetPwd, Role.OPERATOR, true);

        // Anonymous -> 401
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get("/api/admin/users/{id}", target.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequestDto("new.user", ephemeralStrongPassword(), "New User", Role.OPERATOR))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/users/{id}", target.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserProfileRequestDto("Updated Name"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/users/{id}/status", target.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(false))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/users/{id}/role", target.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRoleRequestDto(Role.QC_ANALYST))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/users/{id}/password", target.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetUserPasswordRequestDto(ephemeralStrongPassword()))))
                .andExpect(status().isUnauthorized());

        // Every non-ADMIN role -> 403 Forbidden
        for (Role nonAdminRole : new Role[]{Role.PRODUCTION_SUPERVISOR, Role.OPERATOR, Role.QC_ANALYST, Role.QA_REVIEWER}) {
            String pwd = ephemeralStrongPassword();
            UserAccount nonAdmin = createPersistedUser("role_" + nonAdminRole.name().toLowerCase(), pwd, nonAdminRole, true);
            String token = loginAndExtractToken(nonAdmin.getUsername(), pwd);

            mockMvc.perform(get("/api/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"));

            mockMvc.perform(get("/api/admin/users/{id}", target.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/admin/users")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CreateUserRequestDto("blocked.create", ephemeralStrongPassword(), "Blocked", Role.OPERATOR))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/admin/users/{id}", target.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new UpdateUserProfileRequestDto("Blocked Edit"))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/admin/users/{id}/status", target.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(false))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/admin/users/{id}/role", target.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new UpdateUserRoleRequestDto(Role.QA_REVIEWER))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/admin/users/{id}/password", target.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ResetUserPasswordRequestDto(ephemeralStrongPassword()))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("2. ADMIN can list and create users with normalized lowercase username, BCrypt hash, never exposing passwordHash, and enforcing strict validation")
    void adminCreateAndListUsersWithStrictValidation() throws Exception {
        String adminPwd = ephemeralStrongPassword();
        UserAccount admin = createPersistedUser("sysadmin", adminPwd, Role.ADMIN, true);
        String adminToken = loginAndExtractToken(admin.getUsername(), adminPwd);

        String operatorPwd = ephemeralStrongPassword();
        CreateUserRequestDto createDto = new CreateUserRequestDto(
                "Line.Operator_01",
                operatorPwd,
                "  Ananya Verma  ",
                Role.OPERATOR,
                Boolean.TRUE
        );

        MvcResult createResult = mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("line.operator_01"))
                .andExpect(jsonPath("$.fullName").value("Ananya Verma"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString(operatorPwd))))
                .andReturn();

        Long createdId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();
        UserAccount persisted = userAccountRepository.findById(createdId).orElseThrow();
        assertEquals("line.operator_01", persisted.getUsername());
        assertEquals("Ananya Verma", persisted.getFullName());
        assertNotEquals(operatorPwd, persisted.getPasswordHash());
        assertTrue(passwordEncoder.matches(operatorPwd, persisted.getPasswordHash()));

        // Newly created user can immediately authenticate via POST /api/auth/login
        String createdUserToken = loginAndExtractToken("line.operator_01", operatorPwd);
        assertNotNull(createdUserToken);

        // List users returns both users without passwordHash
        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString(persisted.getPasswordHash()))));

        // Get user by ID returns single user safely; missing ID returns 404
        mockMvc.perform(get("/api/admin/users/{id}", createdId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId))
                .andExpect(jsonPath("$.username").value("line.operator_01"))
                .andExpect(jsonPath("$.fullName").value("Ananya Verma"))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString(operatorPwd))));

        mockMvc.perform(get("/api/admin/users/{id}", 999888L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        // Audit trail captured USER_CREATED without leaking passwords or hashes
        List<AuditLog> createdLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                AdminUserService.ENTITY_USER_ACCOUNT, createdId);
        assertEquals(1, createdLogs.size());
        AuditLog createLog = createdLogs.get(0);
        assertEquals("USER_CREATED", createLog.getAction());
        assertEquals(admin.getUsername(), createLog.getActorName());
        assertEquals("line.operator_01", createLog.getEntityReference());
        assertNull(createLog.getBatchId());
        assertNull(createLog.getBeforeValues());
        JsonNode createdAfter = objectMapper.readTree(createLog.getAfterValues());
        assertEquals("line.operator_01", createdAfter.get("username").asText());
        assertEquals("Ananya Verma", createdAfter.get("fullName").asText());
        assertEquals("OPERATOR", createdAfter.get("role").asText());
        assertTrue(createdAfter.get("enabled").asBoolean());
        assertFalse(createLog.getAfterValues().contains(operatorPwd));
        assertFalse(createLog.getAfterValues().contains(persisted.getPasswordHash()));

        // Audit entry is queryable through Quality Audit Logs API
        mockMvc.perform(get("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("entityType", AdminUserService.ENTITY_USER_ACCOUNT)
                        .param("entityId", String.valueOf(createdId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("USER_CREATED"))
                .andExpect(jsonPath("$[0].actorName").value(admin.getUsername()));

        // Case-insensitive duplicate username -> 409 Conflict
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequestDto("LINE.OPERATOR_01", ephemeralStrongPassword(), "Duplicate User", Role.QC_ANALYST))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // Strict validation errors -> 400 Bad Request
        // 1) Invalid username (spaces / too short / symbol)
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequestDto("bad user!", ephemeralStrongPassword(), "Bad User", Role.OPERATOR))))
                .andExpect(status().isBadRequest());

        // 2) Short password
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequestDto("valid.user2", "Ab1234", "Valid Name", Role.OPERATOR))))
                .andExpect(status().isBadRequest());

        // 3) Password lacking digit or letter
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequestDto("valid.user3", "onlylettersonly", "Valid Name", Role.OPERATOR))))
                .andExpect(status().isBadRequest());

        // 4) Password equal to username
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateUserRequestDto("SameValue9", "SameValue9", "Same Name", Role.OPERATOR))))
                .andExpect(status().isBadRequest());

        // 5) Invalid role enum
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"valid.user4\",\"password\":\"" + ephemeralStrongPassword() + "\",\"fullName\":\"Bad Role\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());

        // 6) Caller-supplied ID rejected on creation -> 400 Bad Request (no overwrite risk)
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + persisted.getId() + ",\"username\":\"valid.user5\",\"password\":\"" + ephemeralStrongPassword() + "\",\"fullName\":\"Injected Id\",\"role\":\"OPERATOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.id").exists());
    }

    @Test
    @DisplayName("3. Status disable and role change increment tokenVersion, revoke active JWTs, enforce self-disable and last-admin guards")
    void statusAndRoleChangesRevokeTokensAndEnforceAdminGuardrails() throws Exception {
        String primaryAdminPwd = ephemeralStrongPassword();
        UserAccount primaryAdmin = createPersistedUser("onlyadmin", primaryAdminPwd, Role.ADMIN, true);
        String primaryAdminToken = loginAndExtractToken(primaryAdmin.getUsername(), primaryAdminPwd);

        String workerPwd = ephemeralStrongPassword();
        UserAccount worker = createPersistedUser("line_worker", workerPwd, Role.OPERATOR, true);
        String workerOriginalToken = loginAndExtractToken(worker.getUsername(), workerPwd);

        // Guard 1: Self-disable forbidden (400), token stays valid
        mockMvc.perform(patch("/api/admin/users/{id}/status", primaryAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("own account")));

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken))
                .andExpect(status().isOk());

        // Guard 2: Demoting the last enabled ADMIN forbidden (400)
        mockMvc.perform(patch("/api/admin/users/{id}/role", primaryAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRoleRequestDto(Role.OPERATOR))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("last enabled ADMIN")));

        // Guard 3: Disabling the last enabled ADMIN forbidden even when invoked by another actor ID
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "external.operator",
                            "N/A",
                            AuthorityUtils.createAuthorityList("ROLE_ADMIN")
                    )
            );
            var ex = assertThrows(com.pharmatrack.exception.InvalidUserOperationException.class,
                    () -> adminUserService.updateUserStatus(
                            primaryAdmin.getId(),
                            new UpdateUserStatusRequestDto(false),
                            999999L,
                            "external.operator"
                    ));
            assertTrue(ex.getMessage().contains("last enabled ADMIN"));
        } finally {
            SecurityContextHolder.clearContext();
        }

        // Disable worker -> sets enabled=false, increments tokenVersion, revokes worker's active JWT and blocks login
        long initialWorkerVersion = worker.getTokenVersion();
        mockMvc.perform(patch("/api/admin/users/{id}/status", worker.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        UserAccount disabledWorker = userAccountRepository.findById(worker.getId()).orElseThrow();
        assertEquals(initialWorkerVersion + 1, disabledWorker.getTokenVersion());

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerOriginalToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(worker.getUsername(), workerPwd))))
                .andExpect(status().isUnauthorized());

        // Re-enable worker -> increments tokenVersion; old pre-disable token remains revoked, new login works
        mockMvc.perform(patch("/api/admin/users/{id}/status", worker.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerOriginalToken))
                .andExpect(status().isUnauthorized());

        String workerReenabledToken = loginAndExtractToken(worker.getUsername(), workerPwd);
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerReenabledToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"));

        // Change worker role -> increments tokenVersion, invalidates workerReenabledToken, new token carries new role
        long versionBeforeRoleChange = userAccountRepository.findById(worker.getId()).orElseThrow().getTokenVersion();
        mockMvc.perform(patch("/api/admin/users/{id}/role", worker.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRoleRequestDto(Role.QC_ANALYST))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("QC_ANALYST"));

        assertEquals(versionBeforeRoleChange + 1,
                userAccountRepository.findById(worker.getId()).orElseThrow().getTokenVersion());

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerReenabledToken))
                .andExpect(status().isUnauthorized());

        String workerPromotedToken = loginAndExtractToken(worker.getUsername(), workerPwd);
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerPromotedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("QC_ANALYST"));

        // Missing user -> 404 Not Found
        mockMvc.perform(patch("/api/admin/users/{id}/status", 888888L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(false))))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/admin/users/{id}/role", 888888L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRoleRequestDto(Role.QA_REVIEWER))))
                .andExpect(status().isNotFound());

        // Multi-admin scenario: when two enabled ADMINs exist, admin A can disable or demote admin B while last-admin remains protected
        String secondAdminPwd = ephemeralStrongPassword();
        UserAccount secondAdmin = createPersistedUser("second_admin", secondAdminPwd, Role.ADMIN, true);
        String secondAdminToken = loginAndExtractToken(secondAdmin.getUsername(), secondAdminPwd);

        // Disable secondAdmin via HTTP -> 200 OK, revokes secondAdminToken
        mockMvc.perform(patch("/api/admin/users/{id}/status", secondAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondAdminToken))
                .andExpect(status().isUnauthorized());

        // Re-enable then demote secondAdmin -> 200 OK
        mockMvc.perform(patch("/api/admin/users/{id}/status", secondAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(patch("/api/admin/users/{id}/role", secondAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRoleRequestDto(Role.PRODUCTION_SUPERVISOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PRODUCTION_SUPERVISOR"));

        // Now primaryAdmin is the sole remaining enabled ADMIN again, so demotion is blocked once more
        mockMvc.perform(patch("/api/admin/users/{id}/role", primaryAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + primaryAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRoleRequestDto(Role.OPERATOR))))
                .andExpect(status().isBadRequest());

        // Verify worker audit trail recorded exact USER_DISABLED, USER_ENABLED, USER_ROLE_UPDATED events (newest first)
        List<AuditLog> workerLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                AdminUserService.ENTITY_USER_ACCOUNT, worker.getId());
        assertEquals(3, workerLogs.size());
        assertEquals("USER_ROLE_UPDATED", workerLogs.get(0).getAction());
        assertEquals(primaryAdmin.getUsername(), workerLogs.get(0).getActorName());
        assertEquals("OPERATOR", objectMapper.readTree(workerLogs.get(0).getBeforeValues()).get("role").asText());
        assertEquals("QC_ANALYST", objectMapper.readTree(workerLogs.get(0).getAfterValues()).get("role").asText());

        assertEquals("USER_ENABLED", workerLogs.get(1).getAction());
        assertFalse(objectMapper.readTree(workerLogs.get(1).getBeforeValues()).get("enabled").asBoolean());
        assertTrue(objectMapper.readTree(workerLogs.get(1).getAfterValues()).get("enabled").asBoolean());

        assertEquals("USER_DISABLED", workerLogs.get(2).getAction());
        assertTrue(objectMapper.readTree(workerLogs.get(2).getBeforeValues()).get("enabled").asBoolean());
        assertFalse(objectMapper.readTree(workerLogs.get(2).getAfterValues()).get("enabled").asBoolean());

        // Failed self-disable and sole-admin demotion created ZERO audit entries for primaryAdmin
        assertTrue(auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                AdminUserService.ENTITY_USER_ACCOUNT, primaryAdmin.getId()).isEmpty());
    }

    @Test
    @DisplayName("4. ADMIN can update user profile fullName while preserving immutable username, validating inputs, skipping no-ops, and recording USER_PROFILE_UPDATED audits")
    void adminCanUpdateUserProfileWithValidationAndAudit() throws Exception {
        String adminPwd = ephemeralStrongPassword();
        UserAccount admin = createPersistedUser("profile_admin", adminPwd, Role.ADMIN, true);
        String adminToken = loginAndExtractToken(admin.getUsername(), adminPwd);

        String targetPwd = ephemeralStrongPassword();
        UserAccount target = createPersistedUser("profile_target", targetPwd, Role.QC_ANALYST, true);
        String initialHash = target.getPasswordHash();
        long initialTokenVersion = target.getTokenVersion();

        // Update fullName with whitespace trimming and forged X-Actor-Name ignored
        mockMvc.perform(patch("/api/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header("X-Actor-Name", "spoofed.actor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"  Dr. Kavita Iyer  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId()))
                .andExpect(jsonPath("$.username").value(target.getUsername()))
                .andExpect(jsonPath("$.fullName").value("Dr. Kavita Iyer"))
                .andExpect(jsonPath("$.role").value("QC_ANALYST"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(content().string(not(containsString("passwordHash"))));

        UserAccount updated = userAccountRepository.findById(target.getId()).orElseThrow();
        assertEquals("Dr. Kavita Iyer", updated.getFullName());
        assertEquals(target.getUsername(), updated.getUsername());
        assertEquals(Role.QC_ANALYST, updated.getRole());
        assertEquals(initialHash, updated.getPasswordHash());
        assertEquals(initialTokenVersion, updated.getTokenVersion());

        List<AuditLog> profileLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                AdminUserService.ENTITY_USER_ACCOUNT, target.getId());
        assertEquals(1, profileLogs.size());
        AuditLog profileLog = profileLogs.get(0);
        assertEquals("USER_PROFILE_UPDATED", profileLog.getAction());
        assertEquals(admin.getUsername(), profileLog.getActorName());
        assertEquals(target.getUsername(), profileLog.getEntityReference());
        assertEquals("Dr. Kavita Iyer", objectMapper.readTree(profileLog.getAfterValues()).get("fullName").asText());
        assertNotEquals("Dr. Kavita Iyer", objectMapper.readTree(profileLog.getBeforeValues()).get("fullName").asText());

        // Idempotent no-op update leaves audit log count unchanged
        mockMvc.perform(patch("/api/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserProfileRequestDto("  Dr. Kavita Iyer  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Dr. Kavita Iyer"));
        assertEquals(1, auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                AdminUserService.ENTITY_USER_ACCOUNT, target.getId()).size());

        // Validation errors -> 400 Bad Request, zero DB or audit mutations
        // 1) Blank fullName
        mockMvc.perform(patch("/api/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.fullName").exists());

        // 2) Single-character fullName
        mockMvc.perform(patch("/api/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserProfileRequestDto("A"))))
                .andExpect(status().isBadRequest());

        // 3) Control characters in fullName
        mockMvc.perform(patch("/api/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserProfileRequestDto("Bad\u0007Name"))))
                .andExpect(status().isBadRequest());

        // 4) Caller-supplied id rejected
        mockMvc.perform(patch("/api/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":999,\"fullName\":\"Injected Profile\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.id").exists());

        // Missing user -> 404 Not Found
        mockMvc.perform(patch("/api/admin/users/{id}", 999777L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserProfileRequestDto("Ghost User"))))
                .andExpect(status().isNotFound());

        assertEquals(1, auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                AdminUserService.ENTITY_USER_ACCOUNT, target.getId()).size());
    }

    @Test
    @DisplayName("5. ADMIN can reset user temporary password with BCrypt, JWT revocation, disabled-state preservation, policy validation, and secret-free USER_PASSWORD_RESET audit")
    void adminCanResetUserPasswordWithTokenRevocationAndSecretFreeAudit() throws Exception {
        String adminPwd = ephemeralStrongPassword();
        UserAccount admin = createPersistedUser("reset_admin", adminPwd, Role.ADMIN, true);
        String adminToken = loginAndExtractToken(admin.getUsername(), adminPwd);

        String oldTargetPwd = ephemeralStrongPassword();
        UserAccount target = createPersistedUser("reset_target", oldTargetPwd, Role.OPERATOR, true);
        String oldTargetJwt = loginAndExtractToken(target.getUsername(), oldTargetPwd);
        long versionBeforeReset = userAccountRepository.findById(target.getId()).orElseThrow().getTokenVersion();

        // Reset temporary password -> 200 OK, response contains no password or hash
        String newTempPwd = ephemeralStrongPassword();
        mockMvc.perform(patch("/api/admin/users/{id}/password", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetUserPasswordRequestDto(newTempPwd))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId()))
                .andExpect(jsonPath("$.username").value(target.getUsername()))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andExpect(content().string(not(containsString(newTempPwd))));

        UserAccount reloaded = userAccountRepository.findById(target.getId()).orElseThrow();
        assertEquals(versionBeforeReset + 1, reloaded.getTokenVersion());
        assertTrue(passwordEncoder.matches(newTempPwd, reloaded.getPasswordHash()));
        assertFalse(passwordEncoder.matches(oldTargetPwd, reloaded.getPasswordHash()));

        // Prior active JWT is immediately invalidated
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldTargetJwt))
                .andExpect(status().isUnauthorized());

        // Old password stops authenticating; new temporary password authenticates cleanly
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(target.getUsername(), oldTargetPwd))))
                .andExpect(status().isUnauthorized());

        String freshTargetJwt = loginAndExtractToken(target.getUsername(), newTempPwd);
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + freshTargetJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(target.getUsername()));

        // Audit trail records USER_PASSWORD_RESET cleanly without leaking plaintext password, BCrypt hash, or JWT
        List<AuditLog> resetLogs = auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                AdminUserService.ENTITY_USER_ACCOUNT, target.getId());
        assertEquals(1, resetLogs.size());
        AuditLog resetLog = resetLogs.get(0);
        assertEquals("USER_PASSWORD_RESET", resetLog.getAction());
        assertEquals(admin.getUsername(), resetLog.getActorName());
        assertEquals(target.getUsername(), resetLog.getEntityReference());
        assertFalse(objectMapper.readTree(resetLog.getBeforeValues()).get("activeSessionsRevoked").asBoolean());
        assertTrue(objectMapper.readTree(resetLog.getAfterValues()).get("activeSessionsRevoked").asBoolean());
        for (String text : new String[]{resetLog.getReason(), resetLog.getBeforeValues(), resetLog.getAfterValues()}) {
            assertFalse(text.contains(newTempPwd));
            assertFalse(text.contains(reloaded.getPasswordHash()));
            assertFalse(text.contains(oldTargetJwt));
            assertFalse(text.contains("tokenVersion"));
        }

        // Disabled user stays disabled after password reset until explicitly re-enabled
        String disabledPwd = ephemeralStrongPassword();
        UserAccount disabledUser = createPersistedUser("disabled_target", disabledPwd, Role.QC_ANALYST, false);
        String disabledNewPwd = ephemeralStrongPassword();
        mockMvc.perform(patch("/api/admin/users/{id}/password", disabledUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetUserPasswordRequestDto(disabledNewPwd))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(disabledUser.getUsername(), disabledNewPwd))))
                .andExpect(status().isUnauthorized());

        // Validation guardrails on password reset -> 400 Bad Request, passwordHash unchanged
        String hashBeforeInvalid = userAccountRepository.findById(target.getId()).orElseThrow().getPasswordHash();
        for (String invalidPwd : new String[]{
                "Short1",
                "onlylettersonly",
                "1234567890",
                target.getUsername().toUpperCase(),
                "BadPass\u00079",
                "é".repeat(40) + "a1"
        }) {
            mockMvc.perform(patch("/api/admin/users/{id}/password", target.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new ResetUserPasswordRequestDto(invalidPwd))))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(patch("/api/admin/users/{id}/password", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":123,\"newPassword\":\"" + ephemeralStrongPassword() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.id").exists());

        assertEquals(hashBeforeInvalid, userAccountRepository.findById(target.getId()).orElseThrow().getPasswordHash());
        assertEquals(1, auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDescIdDesc(
                AdminUserService.ENTITY_USER_ACCOUNT, target.getId()).size());

        // Missing user -> 404 Not Found
        mockMvc.perform(patch("/api/admin/users/{id}/password", 999666L)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetUserPasswordRequestDto(ephemeralStrongPassword()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("6. Direct mutating AdminUserService invocations without SecurityContext fail closed cleanly")
    void directMutatingServiceCallsWithoutSecurityContextFailClosed() {
        UserAccount target = createPersistedUser("unauth_service_target", ephemeralStrongPassword(), Role.OPERATOR, true);
        SecurityContextHolder.clearContext();

        assertThrows(AuthenticationCredentialsNotFoundException.class, () ->
                adminUserService.createUser(new CreateUserRequestDto("blocked.direct", ephemeralStrongPassword(), "Blocked", Role.OPERATOR)));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () ->
                adminUserService.updateUserProfile(target.getId(), new UpdateUserProfileRequestDto("Blocked Rename")));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () ->
                adminUserService.updateUserStatus(target.getId(), new UpdateUserStatusRequestDto(false), null, null));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () ->
                adminUserService.updateUserRole(target.getId(), new UpdateUserRoleRequestDto(Role.QA_REVIEWER)));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () ->
                adminUserService.resetUserPassword(target.getId(), new ResetUserPasswordRequestDto(ephemeralStrongPassword())));
    }
}

