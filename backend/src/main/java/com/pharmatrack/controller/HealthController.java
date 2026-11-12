package com.pharmatrack.controller;

import com.pharmatrack.dto.HealthResponseDto;
import com.pharmatrack.exception.SensitiveDataSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Public operational health endpoint reporting sanitized service and database connectivity status
 * without exposing database catalog names, JDBC URLs, credentials, or exception messages.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private static final String UP_SERVICE_ONLY_MESSAGE = "PharmaTrack backend service is operational";
    private static final String UP_WITH_DB_MESSAGE = "PharmaTrack backend service and database connectivity are operational";
    private static final String DOWN_DB_MESSAGE = "Service unavailable: database connectivity check failed";

    private final DataSource dataSource;

    public HealthController(@Autowired(required = false) DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponseDto> checkHealth() {
        if (dataSource == null) {
            return ResponseEntity.ok(new HealthResponseDto("UP", UP_SERVICE_ONLY_MESSAGE));
        }

        try (Connection conn = dataSource.getConnection()) {
            if (conn != null && conn.isValid(2)) {
                return ResponseEntity.ok(new HealthResponseDto("UP", UP_WITH_DB_MESSAGE));
            }
            log.warn("Health check reported DOWN: database connection validation returned false");
        } catch (Exception ex) {
            log.error("Health check database connectivity error: {}",
                    SensitiveDataSanitizer.formatSafeExceptionForLog(ex));
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponseDto("DOWN", DOWN_DB_MESSAGE));
    }
}
