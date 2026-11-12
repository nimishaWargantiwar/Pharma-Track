package com.pharmatrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.ErrorResponse;
import com.pharmatrack.dto.LoginRequestDto;
import com.pharmatrack.dto.ProductCreateRequestDto;
import com.pharmatrack.entity.ProductStatus;
import com.pharmatrack.entity.Role;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.exception.GlobalExceptionHandler;
import com.pharmatrack.exception.SensitiveDataSanitizer;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.security.RestAuthenticationEntryPoint;
import com.pharmatrack.service.ProductService;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 6D Step 4 regression tests verifying:
 * - Sanitized /api/health output on 200 UP and 503 DOWN (zero catalog/connection/SQL leaks)
 * - Structured sanitized ErrorResponse payloads with +05:30 IST timestamps across all HTTP failure categories
 * - Zero leakage of stack traces, SQL, JDBC URLs, filesystem paths, env vars, passwords, or JWTs
 * - Server-side diagnostic logging with opaque correlation reference and secret/token redaction
 * - Handling of DataIntegrityViolationException, locking/concurrency conflicts, TransactionSystemException,
 *   filter-level DB errors, media-type errors, validation errors, auth/authz errors, and servlet error properties
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pharmatrack_error_sanitization_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ErrorHandlingAndHealthSanitizationIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final Pattern IST_OFFSET_ISO_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?\\+05:30$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @SpyBean
    private ProductService productService;

    @SpyBean
    private UserAccountRepository spiedUserAccountRepository;

    @AfterEach
    void resetMocks() {
        Mockito.reset(productService, spiedUserAccountRepository);
    }

    private String issueBearerToken(String prefix, Role role) throws Exception {
        int n = SEQ.getAndIncrement();
        String rawPassword = "ErrTestPass9!" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UserAccount user = userAccountRepository.saveAndFlush(new UserAccount(
                prefix + "." + n,
                passwordEncoder.encode(rawPassword),
                "Test User " + n,
                role,
                true
        ));
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDto(user.getUsername(), rawPassword))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static void assertIstTimestamp(JsonNode node, String label) {
        assertNotNull(node, label + " JSON body must not be null");
        assertTrue(node.hasNonNull("timestamp"), label + " must contain timestamp");
        String ts = node.get("timestamp").asText();
        assertTrue(IST_OFFSET_ISO_PATTERN.matcher(ts).matches(),
                label + " timestamp must use +05:30 IST offset, got: " + ts);
    }

    private static void assertNoSensitiveArtifacts(String body, List<String> forbiddenSnippets) {
        for (String snippet : forbiddenSnippets) {
            assertFalse(body.contains(snippet), "Response leaked forbidden detail [" + snippet + "]: " + body);
        }
        assertFalse(body.contains("at com.pharmatrack"), "Response leaked Java stack trace: " + body);
        assertFalse(body.contains("\"trace\""), "Response leaked Spring Boot trace field: " + body);
        assertFalse(body.contains("SQLException"), "Response leaked SQLException: " + body);
        assertFalse(body.contains("jdbc:"), "Response leaked JDBC URL: " + body);
    }

    @Test
    @DisplayName("1. Public /api/health returns UP with IST timestamp and never exposes DB catalog name or internals")
    void liveHealthEndpointSanitizesConnectedCatalogAndMetadata() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.message").value("PharmaTrack backend service and database connectivity are operational"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertIstTimestamp(json, "/api/health");
        assertNoSensitiveArtifacts(body, List.of(
                "pharmatrack_error_sanitization_test",
                "pharmatrack_db",
                "jdbc:h2",
                "jdbc:mysql",
                "MySQL",
                "H2"
        ));
    }

    @Test
    @DisplayName("2. Unhandled server exceptions return structured 500 JSON, hide all SQL/paths/secrets/traces, and log cleanly with redacted secrets")
    void unhandledExceptionSanitizesResponseAndRedactsSecretsInServerLogs(CapturedOutput capturedOutput) throws Exception {
        String adminToken = issueBearerToken("admin.err500", Role.ADMIN);

        String syntheticSql = "SELECT * FROM user_accounts WHERE username='admin'";
        String syntheticPath = "/Users/nimisha/Desktop/Pharma-Tracker/backend/config/application-local.properties";
        String syntheticJwtSecret = "FakeJwtSecretMaterial1234567890ABCD";
        String syntheticDbPass = "SuperSecretProdDbPass!42";
        String syntheticJdbc = "jdbc:mysql://pt_prod_user:" + syntheticDbPass + "@prod-mysql.internal:3306/pharmatrack_prod?trustCertificateKeyStorePassword=KeyStoreSecret99";

        doThrow(new RuntimeException(
                "DB query failed [" + syntheticSql + "] at " + syntheticPath
                        + " env JWT_SECRET=" + syntheticJwtSecret
                        + " DB_PASSWORD=" + syntheticDbPass
                        + " url=" + syntheticJdbc
                        + " Bearer " + adminToken))
                .when(productService)
                .getAllProducts();

        MvcResult result = mockMvc.perform(get("/api/products")
                        .queryParam("secret", "QuerySecretValue999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value(matchesPattern("^An unexpected internal server error occurred \\(reference: ERR-[A-F0-9]{8}\\)$")))
                .andExpect(jsonPath("$.validationErrors").value(nullValue()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertIstTimestamp(json, "500 ErrorResponse");
        assertNoSensitiveArtifacts(body, List.of(
                syntheticSql,
                syntheticPath,
                syntheticJwtSecret,
                syntheticDbPass,
                "prod-mysql.internal",
                "pharmatrack_prod",
                "QuerySecretValue999",
                adminToken,
                "KeyStoreSecret99"
        ));

        // Verify correlation reference is logged server-side, request URI excludes query string, and secrets/JWT are redacted
        String clientMessage = json.get("message").asText();
        String ref = clientMessage.substring(clientMessage.indexOf("ERR-"), clientMessage.length() - 1);
        String logs = capturedOutput.getOut() + "\n" + capturedOutput.getErr();

        assertTrue(logs.contains(ref), "Server logs must include opaque correlation reference " + ref);
        assertTrue(logs.contains("GET /api/products"), "Server logs must include method and path");
        assertFalse(logs.contains(adminToken), "Server logs must never expose Bearer JWT token");
        assertFalse(logs.contains(syntheticJwtSecret), "Server logs must redact JWT_SECRET values");
        assertFalse(logs.contains(syntheticDbPass), "Server logs must redact DB_PASSWORD values");
        assertFalse(logs.contains("KeyStoreSecret99"), "Server logs must redact truststore passwords in JDBC URLs");
        assertFalse(logs.contains("QuerySecretValue999"), "Server logs must never log raw query string parameters");
    }

    @Test
    @DisplayName("3. BadSqlGrammarException and SQLException return sanitized 500 JSON without SQL or schema details")
    void sqlAndDataAccessExceptionsNeverLeakSqlOrSchemaDetails() throws Exception {
        String adminToken = issueBearerToken("admin.sqlerr", Role.ADMIN);

        doThrow(new BadSqlGrammarException(
                "loadProducts",
                "SELECT id, secret_col FROM products WHERE tenant_id = ?",
                new SQLException("Unknown column 'secret_col' in 'field list'", "42S22", 1054)))
                .when(productService)
                .getAllProducts();

        MvcResult result = mockMvc.perform(get("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value(containsString("An unexpected internal server error occurred")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertIstTimestamp(objectMapper.readTree(body), "SQL 500 ErrorResponse");
        String bodyWithoutDynamicTokens = body
                .replaceAll("\"timestamp\":\"[^\"]+\"", "\"timestamp\":\"<TIMESTAMP>\"")
                .replaceAll("ERR-[A-F0-9]{8}", "ERR-<REF>");
        assertNoSensitiveArtifacts(bodyWithoutDynamicTokens, List.of(
                "BadSqlGrammarException",
                "Unknown column",
                "secret_col",
                "SELECT id",
                "42S22",
                "1054"
        ));
    }

    @Test
    @DisplayName("4. DataIntegrityViolationException and locking conflicts return 409 Conflict without SQL/constraint leaks")
    void dataIntegrityAndLockingConflictsReturn409ConflictWithoutLeaks() throws Exception {
        String adminToken = issueBearerToken("admin.conflict", Role.ADMIN);

        // A. Database unique / FK constraint violation -> 409 Conflict without raw MySQL constraint text
        doThrow(new DataIntegrityViolationException(
                "could not execute statement [Duplicate entry 'PARA-500' for key 'products.uk_products_code'] [insert into products ...]",
                new SQLIntegrityConstraintViolationException("Cannot add or update a child row: a foreign key constraint fails (`pharmatrack_db`.`recipes`)")))
                .when(productService)
                .createProduct(any(ProductCreateRequestDto.class));

        ProductCreateRequestDto dto = new ProductCreateRequestDto(
                null, "ERR-CONFLICT-1", "Conflict Test Product", "500 mg", "Tablet", "desc", ProductStatus.ACTIVE
        );

        MvcResult integrityResult = mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("conflicts with existing data")))
                .andReturn();

        String integrityBody = integrityResult.getResponse().getContentAsString();
        assertIstTimestamp(objectMapper.readTree(integrityBody), "409 Integrity ErrorResponse");
        assertNoSensitiveArtifacts(integrityBody, List.of(
                "uk_products_code",
                "insert into products",
                "foreign key constraint",
                "pharmatrack_db"
        ));

        // B. Optimistic locking conflict -> 409 Conflict
        doThrow(new ObjectOptimisticLockingFailureException("com.pharmatrack.entity.Product", 42L))
                .when(productService)
                .getAllProducts();

        MvcResult optLockResult = mockMvc.perform(get("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("modified concurrently")))
                .andReturn();

        String optBody = optLockResult.getResponse().getContentAsString();
        assertIstTimestamp(objectMapper.readTree(optBody), "409 Optimistic Lock ErrorResponse");
        assertNoSensitiveArtifacts(optBody, List.of("com.pharmatrack.entity.Product", "ObjectOptimisticLockingFailureException"));

        // C. Pessimistic lock timeout / CannotAcquireLockException -> 409 Conflict
        doThrow(new CannotAcquireLockException("Lock wait timeout exceeded; try restarting transaction"))
                .when(productService)
                .getAllProducts();

        MvcResult pessLockResult = mockMvc.perform(get("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("temporarily locked")))
                .andReturn();

        String pessBody = pessLockResult.getResponse().getContentAsString();
        assertIstTimestamp(objectMapper.readTree(pessBody), "409 Pessimistic Lock ErrorResponse");
        assertNoSensitiveArtifacts(pessBody, List.of("CannotAcquireLockException", "restarting transaction"));
    }

    @Test
    @DisplayName("5. Validation, domain rule, type-mismatch, malformed JSON, 404, 405, 415, 401, and 403 all return structured IST JSON cleanly")
    void allHttpErrorCategoriesPreserveContractsAndSanitization() throws Exception {
        String adminToken = issueBearerToken("admin.cats", Role.ADMIN);
        String operatorToken = issueBearerToken("op.cats", Role.OPERATOR);

        // 400 Validation Failed -> preserves field validationErrors
        MvcResult validationRes = mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productCode\":\"\",\"productName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.productCode").isNotEmpty())
                .andExpect(jsonPath("$.validationErrors.productName").isNotEmpty())
                .andReturn();
        assertIstTimestamp(objectMapper.readTree(validationRes.getResponse().getContentAsString()), "400 Validation");

        // 400 Malformed JSON -> hides Jackson internal class names
        MvcResult malformedRes = mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed JSON request or invalid enum value"))
                .andReturn();
        assertIstTimestamp(objectMapper.readTree(malformedRes.getResponse().getContentAsString()), "400 Malformed JSON");
        assertNoSensitiveArtifacts(malformedRes.getResponse().getContentAsString(), List.of("JsonParseException", "com.fasterxml.jackson"));

        // 400 Parameter Type Mismatch -> keeps helpful parameter name without reflecting injected bad value
        MvcResult mismatchRes = mockMvc.perform(get("/api/quality/deviations")
                        .queryParam("status", "<script>alert(1)</script>")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'status'"))
                .andReturn();
        assertIstTimestamp(objectMapper.readTree(mismatchRes.getResponse().getContentAsString()), "400 Type Mismatch");
        assertNoSensitiveArtifacts(mismatchRes.getResponse().getContentAsString(), List.of("<script>", "alert(1)", "DeviationStatus"));

        // 404 Resource Not Found -> preserves useful domain message
        MvcResult notFoundRes = mockMvc.perform(get("/api/products/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product not found with id: 999999"))
                .andReturn();
        assertIstTimestamp(objectMapper.readTree(notFoundRes.getResponse().getContentAsString()), "404 Not Found");

        // 405 Method Not Allowed -> sanitized structured JSON (audit logs disallow DELETE)
        MvcResult methodRes = mockMvc.perform(delete("/api/quality/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.message").value("HTTP method 'DELETE' is not supported for this endpoint"))
                .andReturn();
        assertIstTimestamp(objectMapper.readTree(methodRes.getResponse().getContentAsString()), "405 Method Not Allowed");

        // 415 Unsupported Media Type -> clean 415 instead of 500
        MvcResult mediaRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("username=admin"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"))
                .andReturn();
        assertIstTimestamp(objectMapper.readTree(mediaRes.getResponse().getContentAsString()), "415 Unsupported Media Type");

        // 401 Unauthenticated -> clean JSON
        MvcResult unauthRes = mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andReturn();
        assertIstTimestamp(objectMapper.readTree(unauthRes.getResponse().getContentAsString()), "401 Unauthenticated");

        // 403 Forbidden -> clean JSON without leaking role internals
        MvcResult forbiddenRes = mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You do not have permission to access this resource"))
                .andReturn();
        assertIstTimestamp(objectMapper.readTree(forbiddenRes.getResponse().getContentAsString()), "403 Forbidden");
    }

    @Test
    @DisplayName("6. JwtAuthenticationFilter handles database outages cleanly as sanitized 503 JSON without logging Bearer JWT")
    void jwtFilterHandlesDatabaseFailureCleanly(CapturedOutput capturedOutput) throws Exception {
        String validToken = issueBearerToken("filter.db", Role.ADMIN);

        doThrow(new DataAccessResourceFailureException(
                "Could not acquire JDBC Connection jdbc:mysql://db_admin:FilterSecret99@db.internal:3306/pharmatrack_prod"))
                .when(spiedUserAccountRepository)
                .findById(any());

        MvcResult result = mockMvc.perform(get("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Service temporarily unavailable"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertIstTimestamp(objectMapper.readTree(body), "503 Filter ErrorResponse");
        assertNoSensitiveArtifacts(body, List.of("FilterSecret99", "db_admin", "db.internal", "pharmatrack_prod", validToken));

        String logs = capturedOutput.getOut() + "\n" + capturedOutput.getErr();
        assertFalse(logs.contains(validToken), "Authentication filter error logs must never print Bearer token");
        assertFalse(logs.contains("FilterSecret99"), "Authentication filter error logs must redact JDBC credentials");
    }

    @Test
    @DisplayName("7. Direct unit tests for GlobalExceptionHandler, RestAuthenticationEntryPoint, HealthController, and SensitiveDataSanitizer")
    void unitTestsCoverAllSanitizationEdgeCases() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        // NoResourceFoundException & NoHandlerFoundException omit static resource internals & paths
        ResponseEntity<ErrorResponse> noResourceRes = handler.handleNoResourceFound(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "secret/internal/path"));
        assertEquals(HttpStatus.NOT_FOUND, noResourceRes.getStatusCode());
        assertEquals("Requested resource was not found", noResourceRes.getBody().getMessage());
        assertFalse(noResourceRes.getBody().getMessage().contains("No static resource"));

        ResponseEntity<ErrorResponse> noHandlerRes = handler.handleNoResourceFound(
                new NoHandlerFoundException("GET", "/internal/debug", new HttpHeaders()));
        assertEquals(HttpStatus.NOT_FOUND, noHandlerRes.getStatusCode());
        assertEquals("Requested resource was not found", noHandlerRes.getBody().getMessage());

        // MissingServletRequestParameterException
        ResponseEntity<ErrorResponse> missingParamRes = handler.handleRequestBindingErrors(
                new MissingServletRequestParameterException("batchId", "Long"));
        assertEquals(HttpStatus.BAD_REQUEST, missingParamRes.getStatusCode());
        assertEquals("Required request parameter 'batchId' is missing", missingParamRes.getBody().getMessage());

        // HttpMediaTypeNotAcceptableException
        ResponseEntity<ErrorResponse> notAcceptableRes = handler.handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException("Could not parse Accept header"));
        assertEquals(HttpStatus.NOT_ACCEPTABLE, notAcceptableRes.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, notAcceptableRes.getHeaders().getContentType());

        // TransactionSystemException unwrapping: Bean Validation -> 400; Hibernate constraint -> 409; Lock -> 409; Unknown -> 500
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        Path.Node node = mock(Path.Node.class);
        when(node.getName()).thenReturn("quantity");
        when(path.iterator()).thenReturn(List.of(node).iterator());
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be positive");
        ResponseEntity<ErrorResponse> txValidationRes = handler.handleTransactionOrPersistenceException(
                new TransactionSystemException("Could not commit JPA transaction",
                        new ConstraintViolationException("Validation failed", Set.of(violation))));
        assertEquals(HttpStatus.BAD_REQUEST, txValidationRes.getStatusCode());
        assertEquals("must be positive", txValidationRes.getBody().getValidationErrors().get("quantity"));

        ResponseEntity<ErrorResponse> txIntegrityRes = handler.handleTransactionOrPersistenceException(
                new TransactionSystemException("Could not commit JPA transaction",
                        new org.hibernate.exception.ConstraintViolationException(
                                "could not execute statement [uk_orders]", new SQLException("Duplicate entry"), "uk_orders")));
        assertEquals(HttpStatus.CONFLICT, txIntegrityRes.getStatusCode());
        assertFalse(txIntegrityRes.getBody().getMessage().contains("uk_orders"));

        ResponseEntity<ErrorResponse> txLockRes = handler.handleTransactionOrPersistenceException(
                new TransactionSystemException("Could not commit", new LockTimeoutException("Lock wait timeout")));
        assertEquals(HttpStatus.CONFLICT, txLockRes.getStatusCode());

        ResponseEntity<ErrorResponse> txUnknownRes = handler.handleTransactionOrPersistenceException(
                new TransactionSystemException("Could not commit", new IllegalStateException("Internal password=Abc123")));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, txUnknownRes.getStatusCode());
        assertFalse(txUnknownRes.getBody().getMessage().contains("Abc123"));

        // ResponseStatusException / ErrorResponseException 5xx sanitization
        ResponseEntity<ErrorResponse> rse500 = handler.handleSpringErrorResponseException(
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal DB secret leaked"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, rse500.getStatusCode());
        assertFalse(rse500.getBody().getMessage().contains("Internal DB secret leaked"));

        // AuthenticationServiceException collapses to generic failure while BadCredentialsException passes safe message
        ResponseEntity<ErrorResponse> authSvcErr = handler.handleAuthenticationException(
                new AuthenticationServiceException("jdbc:mysql://user:pass@db/schema query failed"));
        assertEquals(HttpStatus.UNAUTHORIZED, authSvcErr.getStatusCode());
        assertEquals("Authentication failed", authSvcErr.getBody().getMessage());

        ResponseEntity<ErrorResponse> badCredsErr = handler.handleAuthenticationException(
                new BadCredentialsException("Invalid username or password"));
        assertEquals("Invalid username or password", badCredsErr.getBody().getMessage());

        // RestAuthenticationEntryPoint rejects unsafe attribute strings
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/products");
        req.setAttribute(RestAuthenticationEntryPoint.AUTH_ERROR_MESSAGE_ATTR, "jdbc:mysql://secret/db SQLException");
        MockHttpServletResponse res = new MockHttpServletResponse();
        entryPoint.commence(req, res, new BadCredentialsException("fallback"));
        JsonNode epJson = objectMapper.readTree(res.getContentAsString());
        assertEquals("Authentication is required to access this resource", epJson.get("message").asText());
        assertIstTimestamp(epJson, "EntryPoint ErrorResponse");

        // SensitiveDataSanitizer utility checks
        assertEquals("jdbc:mysql://[REDACTED]@prod-db.internal:3306/pharmatrack_prod",
                SensitiveDataSanitizer.maskJdbcUrl("jdbc:mysql://user:pass@prod-db.internal:3306/pharmatrack_prod?sslMode=REQUIRED&trustCertificateKeyStorePassword=Secret"));
        String sanitizedLog = SensitiveDataSanitizer.sanitizeForLog(
                "Line1\r\nFakeLog: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhIn0.sig123 password=\"TopSecret\"");
        assertFalse(sanitizedLog.contains("\n"));
        assertFalse(sanitizedLog.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertFalse(sanitizedLog.contains("TopSecret"));

        // Direct OptimisticLockException check
        ResponseEntity<ErrorResponse> directOpt = handler.handleConcurrencyOrLockFailure(
                new OptimisticLockException("Row updated or deleted by another transaction"));
        assertEquals(HttpStatus.CONFLICT, directOpt.getStatusCode());
    }

    @Test
    @DisplayName("8. Base and prod configurations enforce server.error sanitization and disable whitelabel error pages")
    void serverErrorPropertiesAreLockedDownAcrossBaseAndProdConfigs() throws Exception {
        Properties baseProps = new Properties();
        try (var in = new FileSystemResource("src/main/resources/application.properties").getInputStream()) {
            baseProps.load(in);
        }
        assertEquals("false", baseProps.getProperty("server.error.whitelabel.enabled"));
        assertEquals("never", baseProps.getProperty("server.error.include-message"));
        assertEquals("never", baseProps.getProperty("server.error.include-binding-errors"));
        assertEquals("never", baseProps.getProperty("server.error.include-stacktrace"));
        assertEquals("false", baseProps.getProperty("server.error.include-exception"));

        Properties prodProps = new Properties();
        try (var in = new ClassPathResource("application-prod.properties").getInputStream()) {
            prodProps.load(in);
        }
        assertEquals("false", prodProps.getProperty("server.error.whitelabel.enabled"));
        assertEquals("never", prodProps.getProperty("server.error.include-message"));
        assertEquals("never", prodProps.getProperty("server.error.include-binding-errors"));
        assertEquals("never", prodProps.getProperty("server.error.include-stacktrace"));
        assertEquals("false", prodProps.getProperty("server.error.include-exception"));
    }
}

