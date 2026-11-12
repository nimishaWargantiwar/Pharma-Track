package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.CreateUserRequestDto;
import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.dto.ResetUserPasswordRequestDto;
import com.pharmatrack.dto.UpdateUserProfileRequestDto;
import com.pharmatrack.dto.UpdateUserRoleRequestDto;
import com.pharmatrack.dto.UpdateUserStatusRequestDto;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.AuditLogRepository;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.JwtService;
import com.pharmatrack.service.AuditTrailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Isolated Spring Boot + H2 integration tests verifying complete transaction rollback
 * across all mutating {@code /api/admin/users/**} operations whenever {@link AuditTrailService#recordAudit}
 * fails, ensuring zero partial account mutations or orphan audit records are committed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_admin_user_rollback_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminUserAuditRollbackIntegrationTest {

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @SpyBean
    private AuditTrailService auditTrailService;

    @AfterEach
    void cleanUp() {
        Mockito.reset(auditTrailService);
        userAccountRepository.deleteAll();
    }

    private static String ephemeralStrongPassword() {
        return "RbSec7" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private UserAccount seedUser(String prefix, String rawPassword, Role role, boolean enabled) {
        int n = SEQ.getAndIncrement();
        return userAccountRepository.saveAndFlush(new UserAccount(
                prefix + "_" + n,
                passwordEncoder.encode(rawPassword),
                "Rollback User " + n,
                role,
                enabled
        ));
    }

    @Test
    @DisplayName("1. Audit failure rolls back user creation, profile update, status toggle, role change, and password reset without partial commits")
    void auditFailureRollsBackAllAdminUserMutationsCompletely() throws Exception {
        String adminPwd = ephemeralStrongPassword();
        UserAccount admin = seedUser("rb_admin", adminPwd, Role.ADMIN, true);
        String adminBearer = "Bearer " + jwtService.generateToken(admin);

        String workerPwd = ephemeralStrongPassword();
        UserAccount worker = seedUser("rb_worker", workerPwd, Role.OPERATOR, true);
        String workerBearer = "Bearer " + jwtService.generateToken(worker);

        String baselineFullName = worker.getFullName();
        String baselineHash = worker.getPasswordHash();
        long baselineVersion = worker.getTokenVersion();
        long usersBaseline = userAccountRepository.count();
        long auditsBaseline = auditLogRepository.count();

        doThrow(new RuntimeException("Injected audit failure for user management rollback"))
                .when(auditTrailService)
                .recordAudit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // 1) Create User rollback
        String rolledBackUsername = "rolledback.user." + SEQ.getAndIncrement();
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateUserRequestDto(
                                rolledBackUsername, ephemeralStrongPassword(), "Rolled Back", Role.QC_ANALYST, true))))
                .andExpect(status().isInternalServerError());

        assertEquals(usersBaseline, userAccountRepository.count());
        assertFalse(userAccountRepository.existsByUsernameIgnoreCase(rolledBackUsername));
        assertEquals(auditsBaseline, auditLogRepository.count());

        // 2) Update Profile rollback
        mockMvc.perform(patch("/api/admin/users/{id}", worker.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserProfileRequestDto("Uncommitted Rename"))))
                .andExpect(status().isInternalServerError());

        UserAccount afterProfileFail = userAccountRepository.findById(worker.getId()).orElseThrow();
        assertEquals(baselineFullName, afterProfileFail.getFullName());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // 3) Status Toggle rollback (worker stays enabled, tokenVersion unchanged, existing JWT still works)
        mockMvc.perform(patch("/api/admin/users/{id}/status", worker.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserStatusRequestDto(false))))
                .andExpect(status().isInternalServerError());

        UserAccount afterStatusFail = userAccountRepository.findById(worker.getId()).orElseThrow();
        assertTrue(afterStatusFail.isEnabled());
        assertEquals(baselineVersion, afterStatusFail.getTokenVersion());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, workerBearer))
                .andExpect(status().isOk());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // 4) Role Update rollback (role stays OPERATOR, tokenVersion unchanged)
        mockMvc.perform(patch("/api/admin/users/{id}/role", worker.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserRoleRequestDto(Role.QA_REVIEWER))))
                .andExpect(status().isInternalServerError());

        UserAccount afterRoleFail = userAccountRepository.findById(worker.getId()).orElseThrow();
        assertEquals(Role.OPERATOR, afterRoleFail.getRole());
        assertEquals(baselineVersion, afterRoleFail.getTokenVersion());
        assertEquals(auditsBaseline, auditLogRepository.count());

        // 5) Password Reset rollback (old password & JWT keep working, attempted temp password rejected)
        String attemptedNewPwd = ephemeralStrongPassword();
        mockMvc.perform(patch("/api/admin/users/{id}/password", worker.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetUserPasswordRequestDto(attemptedNewPwd))))
                .andExpect(status().isInternalServerError());

        UserAccount afterPwdFail = userAccountRepository.findById(worker.getId()).orElseThrow();
        assertEquals(baselineHash, afterPwdFail.getPasswordHash());
        assertEquals(baselineVersion, afterPwdFail.getTokenVersion());
        assertTrue(passwordEncoder.matches(workerPwd, afterPwdFail.getPasswordHash()));
        assertFalse(passwordEncoder.matches(attemptedNewPwd, afterPwdFail.getPasswordHash()));
        assertEquals(auditsBaseline, auditLogRepository.count());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(worker.getUsername(), attemptedNewPwd))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(worker.getUsername(), workerPwd))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(worker.getUsername()));

        // Recovery verification after resetting SpyBean
        Mockito.reset(auditTrailService);
        mockMvc.perform(patch("/api/admin/users/{id}", worker.getId())
                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateUserProfileRequestDto("Recovered Name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Recovered Name"));
        assertEquals(auditsBaseline + 1, auditLogRepository.count());
    }
}

