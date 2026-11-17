package com.pharmatrack.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.access.AccessDeniedException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocalEnvironmentDeletionGuard Fail-Closed Safety Tests")
class LocalEnvironmentDeletionGuardTest {

    @Mock
    private Environment environment;

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private DatabaseMetaData metaData;

    private LocalEnvironmentDeletionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new LocalEnvironmentDeletionGuard(environment, dataSource);
    }

    private void mockEnvironment(boolean isProd, boolean isLocalOrTest, boolean isFlagEnabled) {
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(isProd);
        if (!isProd) {
            when(environment.acceptsProfiles(Profiles.of("local"))).thenReturn(isLocalOrTest);
            if (!isLocalOrTest) {
                when(environment.acceptsProfiles(Profiles.of("test"))).thenReturn(false);
            }
        }
        if (!isProd && isLocalOrTest) {
            when(environment.getProperty(eq(LocalEnvironmentDeletionGuard.FEATURE_FLAG_PROPERTY), eq(Boolean.class), eq(false)))
                    .thenReturn(isFlagEnabled);
        }
    }

    private void mockConnectionUrl(String url) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn(url);
    }

    @Test
    @DisplayName("Tier 1: Fails closed when Spring Environment is null")
    void nullEnvironment_throwsIllegalStateException() {
        LocalEnvironmentDeletionGuard nullEnvGuard = new LocalEnvironmentDeletionGuard(null, dataSource);
        assertThatThrownBy(nullEnvGuard::verifyPermanentDeletionAllowed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Environment is unavailable");
    }

    @Test
    @DisplayName("Tier 1: Fails closed immediately when prod profile is active")
    void prodProfile_throwsAccessDeniedException() {
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(true);

        assertThatThrownBy(() -> guard.verifyPermanentDeletionAllowed())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("strictly prohibited in production");

        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("Tier 1: Fails closed when neither 'local' nor 'test' profile is active")
    void nonLocalProfile_throwsAccessDeniedException() {
        when(environment.acceptsProfiles(Profiles.of("prod"))).thenReturn(false);
        when(environment.acceptsProfiles(Profiles.of("local"))).thenReturn(false);
        when(environment.acceptsProfiles(Profiles.of("test"))).thenReturn(false);

        assertThatThrownBy(() -> guard.verifyPermanentDeletionAllowed())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("requires an active 'local' or 'test' Spring profile");

        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("Tier 2: Fails closed when feature flag is disabled (false)")
    void flagDisabled_throwsAccessDeniedException() {
        mockEnvironment(false, true, false);

        assertThatThrownBy(() -> guard.verifyPermanentDeletionAllowed())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("disabled by configuration");

        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("Tier 3: Fails closed when DataSource is null")
    void nullDataSource_throwsIllegalStateException() {
        mockEnvironment(false, true, true);
        LocalEnvironmentDeletionGuard nullDsGuard = new LocalEnvironmentDeletionGuard(environment, null);

        assertThatThrownBy(nullDsGuard::verifyPermanentDeletionAllowed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DataSource is unavailable");
    }

    @Test
    @DisplayName("Tier 3: Fails closed when DataSource throws SQLException on getConnection")
    void dataSourceThrowsException_throwsIllegalStateException() throws SQLException {
        mockEnvironment(false, true, true);
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        assertThatThrownBy(() -> guard.verifyPermanentDeletionAllowed())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to verify database connection");
    }

    @Test
    @DisplayName("Tier 3: Fails closed when JDBC URL is null or empty")
    void emptyJdbcUrl_throwsIllegalStateException() throws SQLException {
        mockEnvironment(false, true, true);
        mockConnectionUrl("   ");

        assertThatThrownBy(() -> guard.verifyPermanentDeletionAllowed())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC connection URL cannot be determined");
    }

    @Test
    @DisplayName("Tier 3: Rejects TiDB Cloud connection explicitly")
    void tidbCloudUrl_throwsAccessDeniedException() throws SQLException {
        mockEnvironment(false, true, true);
        mockConnectionUrl("jdbc:mysql://gateway01.ap-south-1.prod.aws.tidbcloud.com:4000/pharma_tracker?sslMode=VERIFY_IDENTITY");

        assertThatThrownBy(() -> guard.verifyPermanentDeletionAllowed())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("cloud or production endpoint");
    }

    @Test
    @DisplayName("Tier 3: Rejects AWS cloud database connection")
    void awsCloudUrl_throwsAccessDeniedException() throws SQLException {
        mockEnvironment(false, true, true);
        mockConnectionUrl("jdbc:mysql://pharmatrack-db.c7x8y9z0.us-east-1.rds.amazonaws.com:3306/pharmatrack");

        assertThatThrownBy(() -> guard.verifyPermanentDeletionAllowed())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("cloud or production endpoint");
    }

    @Test
    @DisplayName("Tier 3: Rejects non-loopback LAN host even if DB name is 'local'")
    void remoteHostWithLocalDbName_throwsAccessDeniedException() throws SQLException {
        mockEnvironment(false, true, true);
        mockConnectionUrl("jdbc:mysql://192.168.1.100:3306/pharmatrack_local");

        assertThatThrownBy(() -> guard.verifyPermanentDeletionAllowed())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not a verified loopback host");
    }

    @Test
    @DisplayName("Tier 3: Allows approved H2 in-memory test database")
    void approvedH2Mem_succeeds() throws SQLException {
        mockEnvironment(false, true, true);
        mockConnectionUrl("jdbc:h2:mem:pharmatrack_test;DB_CLOSE_DELAY=-1");

        assertThatCode(() -> guard.verifyPermanentDeletionAllowed())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Tier 3: Allows approved H2 file test database")
    void approvedH2File_succeeds() throws SQLException {
        mockEnvironment(false, true, true);
        mockConnectionUrl("jdbc:h2:file:./data/pharmatrack_test");

        assertThatCode(() -> guard.verifyPermanentDeletionAllowed())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Tier 3: Allows verified loopback localhost MySQL")
    void localMysql_localhost_succeeds() throws SQLException {
        mockEnvironment(false, true, true);
        mockConnectionUrl("jdbc:mysql://localhost:3306/pharma_tracker?useSSL=false");

        assertThatCode(() -> guard.verifyPermanentDeletionAllowed())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Tier 3: Allows verified loopback 127.0.0.1 MySQL")
    void localMysql_127001_succeeds() throws SQLException {
        mockEnvironment(false, true, true);
        mockConnectionUrl("jdbc:mysql://127.0.0.1:3306/pharma_tracker");

        assertThatCode(() -> guard.verifyPermanentDeletionAllowed())
                .doesNotThrowAnyException();
    }
}
