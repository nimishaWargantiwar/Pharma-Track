package com.pharmatrack.config;

import com.pharmatrack.exception.SensitiveDataSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Validates active database connectivity upon startup and logs sanitized connection metadata.
 */
@Component
public class DatabaseConnectionVerifier implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionVerifier.class);
    private final DataSource dataSource;

    public DatabaseConnectionVerifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            log.info("==================================================================");
            log.info("DATABASE CONNECTION VERIFIED SUCCESSFULLY!");
            log.info("Connected to database catalog: {}", conn.getCatalog());
            log.info("Database product: {} v{}", conn.getMetaData().getDatabaseProductName(), conn.getMetaData().getDatabaseProductVersion());
            log.info("Database endpoint: {}", SensitiveDataSanitizer.maskJdbcUrl(conn.getMetaData().getURL()));
            log.info("==================================================================");
        }
    }
}

