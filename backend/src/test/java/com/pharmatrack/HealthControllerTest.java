package com.pharmatrack;

import com.pharmatrack.controller.HealthController;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class HealthControllerTest {

    private static final Pattern IST_OFFSET_ISO_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?\\+05:30$");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/health returns 200 UP with +05:30 timestamp when no DataSource bean is present")
    void healthCheckShouldReturnOkAndUpStatus() throws Exception {
        mockMvc.perform(get("/api/health")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(IST_OFFSET_ISO_PATTERN)))
                .andExpect(content().string(not(containsString("jdbc:"))))
                .andExpect(content().string(not(containsString("pharmatrack_db"))));
    }

    @Test
    @DisplayName("GET /api/health returns 200 UP without exposing database catalog name or connection metadata")
    void healthCheckWithDatabaseDoesNotExposeCatalogOrConnectionDetails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(connection.getCatalog()).thenReturn("pharmatrack_secret_prod_catalog");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(dataSource)).build();
        mvc.perform(get("/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.message").value("PharmaTrack backend service and database connectivity are operational"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(IST_OFFSET_ISO_PATTERN)))
                .andExpect(content().string(not(containsString("pharmatrack_secret_prod_catalog"))))
                .andExpect(content().string(not(containsString("mysql"))))
                .andExpect(content().string(not(containsString("jdbc:"))));
    }

    @Test
    @DisplayName("GET /api/health returns 503 DOWN when connection validation returns false")
    void healthCheckReturns503WhenConnectionValidationReturnsFalse() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(dataSource)).build();
        mvc.perform(get("/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.message").value("Service unavailable: database connectivity check failed"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(IST_OFFSET_ISO_PATTERN)));
    }

    @Test
    @DisplayName("GET /api/health returns 503 DOWN without leaking SQLException, JDBC URL, host, or password details")
    void healthCheckReturns503WithoutLeakingSqlOrConnectionStrings() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException(
                "Communications link failure to jdbc:mysql://pt_prod_user:TopSecretDbPass99!@prod-db.internal:25060/pharmatrack_prod?sslMode=REQUIRED"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new HealthController(dataSource)).build();
        mvc.perform(get("/api/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.message").value("Service unavailable: database connectivity check failed"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(IST_OFFSET_ISO_PATTERN)))
                .andExpect(content().string(not(containsString("Communications link failure"))))
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("jdbc:mysql"))))
                .andExpect(content().string(not(containsString("pt_prod_user"))))
                .andExpect(content().string(not(containsString("TopSecretDbPass99!"))))
                .andExpect(content().string(not(containsString("prod-db.internal"))))
                .andExpect(content().string(not(containsString("pharmatrack_prod"))));
    }
}
