package com.pharmatrack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.UserSummaryDto;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.repository.UserAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistence and model tests for {@link UserAccount} verifying normalized lowercase usernames,
 * case-insensitive uniqueness, {@code Asia/Kolkata} timestamps under UTC JVM default, and
 * non-exposure of {@code passwordHash}.
 */
@DataJpaTest
class UserAccountRepositoryAndTimezoneTest {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    @DisplayName("Normalizes usernames to lowercase, enforces uniqueness across case variants, and uses Asia/Kolkata timestamps")
    void normalizesUsernameEnforcesUniquenessAndUsesIstTimestamps() {
        TimeZone originalTz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            LocalDateTime beforeIst = LocalDateTime.now(IST_ZONE);

            UserAccount account = new UserAccount(
                    "  Supervisor.Line1  ",
                    "$2a$10$syntheticbcryptplaceholderhashvalue00000000000000000",
                    "  Priya Nair  ",
                    Role.PRODUCTION_SUPERVISOR,
                    true
            );
            UserAccount saved = userAccountRepository.saveAndFlush(account);

            assertNotNull(saved.getId());
            assertEquals("supervisor.line1", saved.getUsername());
            assertEquals("Priya Nair", saved.getFullName());
            assertEquals(Role.PRODUCTION_SUPERVISOR, saved.getRole());
            assertTrue(saved.isEnabled());
            assertEquals(0L, saved.getTokenVersion());

            LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
            long minutesAheadOfUtc = Duration.between(utcNow, saved.getCreatedAt()).toMinutes();
            assertTrue(minutesAheadOfUtc >= 325 && minutesAheadOfUtc <= 335,
                    "UserAccount timestamps must use Asia/Kolkata (+05:30)");
            assertFalse(saved.getCreatedAt().isBefore(beforeIst.minusSeconds(2)));

            assertTrue(userAccountRepository.findByUsernameIgnoreCase("SUPERVISOR.LINE1").isPresent());
            assertTrue(userAccountRepository.existsByUsernameIgnoreCase("Supervisor.Line1"));

            UserAccount duplicateCase = new UserAccount(
                    "SUPERVISOR.LINE1",
                    "$2a$10$syntheticbcryptplaceholderhashvalue11111111111111111",
                    "Duplicate Person",
                    Role.OPERATOR,
                    true
            );
            assertThrows(DataIntegrityViolationException.class,
                    () -> userAccountRepository.saveAndFlush(duplicateCase));
        } finally {
            TimeZone.setDefault(originalTz);
        }
    }

    @Test
    @DisplayName("UserAccount JSON, toString, and UserSummaryDto never expose passwordHash")
    void neverExposesPasswordHash() throws Exception {
        String sentinelHash = "$2a$10$secretHashMustNeverSerializeOrPrintInLogs0000000000";
        UserAccount account = new UserAccount("qc_user", sentinelHash, "QC Inspector", Role.QC_ANALYST, true);
        account.setId(9L);

        assertFalse(account.toString().contains(sentinelHash));

        ObjectMapper mapper = new ObjectMapper();
        String entityJson = mapper.writeValueAsString(account);
        assertFalse(entityJson.contains("passwordHash"));
        assertFalse(entityJson.contains(sentinelHash));

        String dtoJson = mapper.writeValueAsString(UserSummaryDto.fromEntity(account));
        assertFalse(dtoJson.contains("passwordHash"));
        assertFalse(dtoJson.contains(sentinelHash));
        assertTrue(dtoJson.contains("QC_ANALYST"));
    }
}

