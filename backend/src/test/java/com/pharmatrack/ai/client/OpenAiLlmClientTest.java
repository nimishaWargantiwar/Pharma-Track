package com.pharmatrack.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.config.AiCopilotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("OpenAI Responses API LLM Client Unit & Integration Tests")
class OpenAiLlmClientTest {

    private static final String TEST_API_KEY = "sk-pharma-test-secret-key-12345";
    private static final String BASE_URL = "https://api.openai.com/v1";

    private AiCopilotProperties properties;
    private ObjectMapper objectMapper;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private OpenAiLlmClient client;

    @BeforeEach
    void setUp() {
        properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey(TEST_API_KEY);
        properties.setBaseUrl(BASE_URL);
        properties.setModel("gpt-6-luna");
        properties.setMaxOutputTokens(800);
        properties.setConnectTimeoutMs(3000);
        properties.setReadTimeoutMs(30000);

        objectMapper = new ObjectMapper();
        restClientBuilder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new OpenAiLlmClient(properties, objectMapper, restClientBuilder);
    }

    @Test
    @DisplayName("Successful Responses API call dispatches correct schema, Bearer auth and parses text with token usage")
    void generate_successfulResponsesApiCall_extractsOutputTextAndUsage() {
        String mockResponseJson = """
                {
                  "id": "resp_test_987654321",
                  "object": "response",
                  "created_at": 1756315696,
                  "model": "gpt-6-luna",
                  "status": "completed",
                  "output": [
                    {
                      "id": "msg_001",
                      "type": "message",
                      "status": "completed",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "Batch BAT-2026-001 is completed with 98.40% yield. Release is pending deviation DEV-2026-001 closure."
                        }
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 85,
                    "output_tokens": 28,
                    "total_tokens": 113
                  }
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TEST_API_KEY))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.model").value("gpt-6-luna"))
                .andExpect(jsonPath("$.instructions").value("You are an advisory MES assistant."))
                .andExpect(jsonPath("$.input").value("Summarize batch BAT-2026-001"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.max_output_tokens").value(800))
                .andRespond(withSuccess(mockResponseJson, MediaType.APPLICATION_JSON));

        LlmResponse response = client.generate("You are an advisory MES assistant.", "Summarize batch BAT-2026-001");

        mockServer.verify();
        assertThat(response).isNotNull();
        assertThat(response.outputText()).isEqualTo("Batch BAT-2026-001 is completed with 98.40% yield. Release is pending deviation DEV-2026-001 closure.");
        assertThat(response.model()).isEqualTo("gpt-6-luna");
        assertThat(response.responseId()).isEqualTo("resp_test_987654321");
        assertThat(response.inputTokens()).isEqualTo(85);
        assertThat(response.outputTokens()).isEqualTo(28);
        assertThat(response.totalTokens()).isEqualTo(113);
    }

    @Test
    @DisplayName("Handles missing optional instructions and formats payload with store=false")
    void generate_withoutInstructions_sendsCleanPayload() {
        String mockResponseJson = """
                {
                  "id": "resp_no_instr_123",
                  "model": "gpt-6-luna",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "General pharmaceutical advice."
                        }
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 10,
                    "output_tokens": 5,
                    "total_tokens": 15
                  }
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.instructions").doesNotExist())
                .andExpect(jsonPath("$.input").value("Define yield reconciliation"))
                .andExpect(jsonPath("$.store").value(false))
                .andRespond(withSuccess(mockResponseJson, MediaType.APPLICATION_JSON));

        LlmResponse response = client.generate(null, "Define yield reconciliation");

        mockServer.verify();
        assertThat(response.outputText()).isEqualTo("General pharmaceutical advice.");
    }

    @Test
    @DisplayName("Throws CONFIGURATION_ERROR when AI Copilot is disabled")
    void generate_whenDisabled_throwsConfigurationError() {
        properties.setEnabled(false);

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.generate("instructions", "input"))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("PharmaTrack AI Copilot is currently disabled")
                .extracting(ex -> ((LlmClientException) ex).getCategory())
                .isEqualTo(LlmClientException.ErrorCategory.CONFIGURATION_ERROR);
    }

    @Test
    @DisplayName("Throws CONFIGURATION_ERROR when API key is missing or blank")
    void generate_whenApiKeyMissing_throwsConfigurationError() {
        properties.setApiKey("   ");

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.generate("instructions", "input"))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("AI provider API key is not configured")
                .extracting(ex -> ((LlmClientException) ex).getCategory())
                .isEqualTo(LlmClientException.ErrorCategory.CONFIGURATION_ERROR);
    }

    @Test
    @DisplayName("Rejects null or blank prompt input with IllegalArgumentException")
    void generate_blankInput_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> client.generate("instructions", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prompt input must not be blank");

        assertThatThrownBy(() -> client.generate("instructions", "    "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prompt input must not be blank");
    }

    @Test
    @DisplayName("HTTP 401 Unauthorized maps to UNAUTHORIZED category without leaking API key")
    void generate_http401_mapsToUnauthorizedException() {
        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Incorrect API key provided\"}}"));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.UNAUTHORIZED);
                    assertThat(lex.getHttpStatus()).isEqualTo(401);
                    assertThat(lex.getMessage()).contains("AI provider authentication failed");
                    assertThat(lex.getMessage()).doesNotContain(TEST_API_KEY);
                });
    }

    @Test
    @DisplayName("HTTP 403 Forbidden maps to FORBIDDEN category")
    void generate_http403_mapsToForbiddenException() {
        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Country or territory unsupported\"}}"));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.FORBIDDEN);
                    assertThat(lex.getHttpStatus()).isEqualTo(403);
                    assertThat(lex.getMessage()).contains("access forbidden");
                });
    }

    @Test
    @DisplayName("HTTP 400 Bad Request maps to BAD_REQUEST category")
    void generate_http400_mapsToBadRequestException() {
        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Unsupported parameter\"}}"));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.BAD_REQUEST);
                    assertThat(lex.getHttpStatus()).isEqualTo(400);
                    assertThat(lex.getMessage()).contains("invalid parameters");
                });
    }

    @Test
    @DisplayName("HTTP 429 Too Many Requests maps to RATE_LIMITED category")
    void generate_http429_mapsToRateLimitedException() {
        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Rate limit exceeded\"}}"));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.RATE_LIMITED);
                    assertThat(lex.getHttpStatus()).isEqualTo(429);
                    assertThat(lex.getMessage()).contains("rate limit reached or quota exhausted");
                });
    }

    @Test
    @DisplayName("HTTP 5xx Server Error maps to SERVER_ERROR category")
    void generate_http500_mapsToServerErrorException() {
        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Internal server error\"}}"));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.SERVER_ERROR);
                    assertThat(lex.getHttpStatus()).isEqualTo(500);
                    assertThat(lex.getMessage()).contains("temporarily unavailable");
                });
    }

    @Test
    @DisplayName("Provider returning error envelope in HTTP 200 payload maps to SERVER_ERROR")
    void generate_providerErrorEnvelope_mapsToServerError() {
        String errorEnvelopeJson = """
                {
                  "error": {
                    "message": "Model overloaded with high traffic",
                    "type": "server_error"
                  }
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(errorEnvelopeJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.SERVER_ERROR);
                    assertThat(lex.getMessage()).contains("Model overloaded with high traffic");
                });
    }

    @Test
    @DisplayName("Responses API payload with status='failed' maps to SERVER_ERROR")
    void generate_statusFailed_mapsToServerError() {
        String failedResponseJson = """
                {
                  "id": "resp_failed_001",
                  "model": "gpt-6-luna",
                  "status": "failed"
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(failedResponseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.SERVER_ERROR);
                    assertThat(lex.getMessage()).contains("AI provider execution terminated with status 'failed'");
                });
    }

    @Test
    @DisplayName("Responses API payload with status='incomplete' rejects partial text and maps to INVALID_RESPONSE")
    void generate_statusIncomplete_mapsToInvalidResponse() {
        String incompleteResponseJson = """
                {
                  "id": "resp_incomplete_001",
                  "model": "gpt-6-luna",
                  "status": "incomplete",
                  "incomplete_details": {
                    "reason": "max_output_tokens"
                  },
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "Partial cut-off text that must never be presented as a verified answer"
                        }
                      ]
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(incompleteResponseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.INVALID_RESPONSE);
                    assertThat(lex.getMessage()).contains("incomplete output (reason: max_output_tokens)");
                });
    }

    @Test
    @DisplayName("Responses API payload with status='incomplete' without details handles missing reason cleanly")
    void generate_statusIncompleteWithoutDetails_mapsToInvalidResponse() {
        String incompleteResponseJson = """
                {
                  "id": "resp_incomplete_002",
                  "model": "gpt-6-luna",
                  "status": "incomplete"
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(incompleteResponseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.INVALID_RESPONSE);
                    assertThat(lex.getMessage()).contains("incomplete output (reason: unspecified)");
                });
    }

    @Test
    @DisplayName("Responses API payload missing required 'status' attribute maps to INVALID_RESPONSE")
    void generate_missingStatus_mapsToInvalidResponse() {
        String missingStatusJson = """
                {
                  "id": "resp_no_status_001",
                  "model": "gpt-6-luna",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "Unverified text without status field"
                        }
                      ]
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(missingStatusJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.INVALID_RESPONSE);
                    assertThat(lex.getMessage()).contains("missing required 'status' attribute");
                });
    }

    @Test
    @DisplayName("Responses API payload with unexpected status maps to INVALID_RESPONSE")
    void generate_unexpectedStatus_mapsToInvalidResponse() {
        String unexpectedStatusJson = """
                {
                  "id": "resp_cancelled_001",
                  "model": "gpt-6-luna",
                  "status": "cancelled"
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(unexpectedStatusJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.INVALID_RESPONSE);
                    assertThat(lex.getMessage()).contains("unexpected completion status: 'cancelled'");
                });
    }

    @Test
    @DisplayName("Malformed JSON response body maps to INVALID_RESPONSE category")
    void generate_malformedJson_mapsToInvalidResponse() {
        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess("INVALID_NON_JSON_PAYLOAD_<<<", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.INVALID_RESPONSE);
                    assertThat(lex.getMessage()).contains("Failed to parse AI provider JSON");
                });
    }

    @Test
    @DisplayName("Empty or missing output text in valid JSON structure maps to INVALID_RESPONSE")
    void generate_emptyOutputText_mapsToInvalidResponse() {
        String emptyOutputJson = """
                {
                  "id": "resp_empty_123",
                  "model": "gpt-6-luna",
                  "status": "completed",
                  "output": []
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess(emptyOutputJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.INVALID_RESPONSE);
                    assertThat(lex.getMessage()).contains("empty or missing output text response");
                });
    }

    @Test
    @DisplayName("Socket read timeout maps to TIMEOUT category")
    void generate_timeout_mapsToTimeoutCategory() {
        RestClient timeoutRestClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new ResourceAccessException("I/O error on POST request: Read timed out",
                            new SocketTimeoutException("Read timed out"));
                })
                .build();

        OpenAiLlmClient timeoutClient = new OpenAiLlmClient(properties, objectMapper, timeoutRestClient);

        assertThatThrownBy(() -> timeoutClient.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.TIMEOUT);
                    assertThat(lex.getMessage()).contains("timed out");
                });
    }

    @Test
    @DisplayName("Connection failure maps to CONNECTION_FAILURE category")
    void generate_connectionFailure_mapsToConnectionFailureCategory() {
        RestClient connFailRestClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new ResourceAccessException("Connection refused",
                            new ConnectException("Connection refused"));
                })
                .build();

        OpenAiLlmClient connFailClient = new OpenAiLlmClient(properties, objectMapper, connFailRestClient);

        assertThatThrownBy(() -> connFailClient.generate("instructions", "test input"))
                .isInstanceOf(LlmClientException.class)
                .satisfies(ex -> {
                    LlmClientException lex = (LlmClientException) ex;
                    assertThat(lex.getCategory()).isEqualTo(LlmClientException.ErrorCategory.CONNECTION_FAILURE);
                    assertThat(lex.getMessage()).contains("Unable to establish connection");
                });
    }

    @Test
    @DisplayName("Base URL with trailing slash is cleanly normalized and dispatches to /responses without double slash")
    void generate_baseUrlWithTrailingSlash_dispatchesCleanEndpoint() {
        AiCopilotProperties slashProps = new AiCopilotProperties();
        slashProps.setEnabled(true);
        slashProps.setApiKey(TEST_API_KEY);
        slashProps.setBaseUrl("https://api.openai.com/v1/");

        String normalizedUrl = AiCopilotProperties.normalizeBaseUrl(slashProps.getBaseUrl());
        assertThat(normalizedUrl).isEqualTo("https://api.openai.com/v1");

        RestClient.Builder customBuilder = RestClient.builder().baseUrl(normalizedUrl);
        MockRestServiceServer customMockServer = MockRestServiceServer.bindTo(customBuilder).build();
        OpenAiLlmClient customClient = new OpenAiLlmClient(slashProps, objectMapper, customBuilder);

        String mockSuccess = """
                {
                  "id": "resp_slash_1",
                  "model": "gpt-6-luna",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [{"type": "output_text", "text": "Normalized response"}]
                    }
                  ]
                }
                """;

        customMockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(mockSuccess, MediaType.APPLICATION_JSON));

        LlmResponse resp = customClient.generate("test instructions", "test prompt");
        customMockServer.verify();
        assertThat(resp.outputText()).isEqualTo("Normalized response");
    }

    @Test
    @DisplayName("Base URL with multiple trailing slashes is normalized to exactly /v1")
    void generate_baseUrlWithMultipleTrailingSlashes_dispatchesCleanEndpoint() {
        AiCopilotProperties multiSlashProps = new AiCopilotProperties();
        multiSlashProps.setEnabled(true);
        multiSlashProps.setApiKey(TEST_API_KEY);
        multiSlashProps.setBaseUrl("https://api.openai.com/v1///");

        String normalizedUrl = AiCopilotProperties.normalizeBaseUrl(multiSlashProps.getBaseUrl());
        assertThat(normalizedUrl).isEqualTo("https://api.openai.com/v1");

        RestClient.Builder customBuilder = RestClient.builder().baseUrl(normalizedUrl);
        MockRestServiceServer customMockServer = MockRestServiceServer.bindTo(customBuilder).build();
        OpenAiLlmClient customClient = new OpenAiLlmClient(multiSlashProps, objectMapper, customBuilder);

        String mockSuccess = """
                {
                  "id": "resp_multi_1",
                  "model": "gpt-6-luna",
                  "status": "completed",
                  "output": [
                    {
                      "type": "message",
                      "content": [{"type": "output_text", "text": "Multi-slash normalized response"}]
                    }
                  ]
                }
                """;

        customMockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(mockSuccess, MediaType.APPLICATION_JSON));

        LlmResponse resp = customClient.generate("test instructions", "test prompt");
        customMockServer.verify();
        assertThat(resp.outputText()).isEqualTo("Multi-slash normalized response");
    }

    @Test
    @DisplayName("Properties getters expose model and provider correctly")
    void clientMetadata_matchesConfiguration() {
        assertThat(client.getModel()).isEqualTo("gpt-6-luna");
        assertThat(client.getProvider()).isEqualTo("openai");
        assertThat(client.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("Defect 1 Fix: Groq Responses API with separate reasoning and message items extracts ONLY final answer")
    void generate_groqResponsesWithSeparateReasoningAndMessage_extractsOnlyFinalAnswerAndExcludesReasoning() {
        String groqReasoningFixture = """
                {
                  "id": "resp_groq_reasoning_12345",
                  "object": "response",
                  "created_at": 1756315800,
                  "model": "openai/gpt-oss-20b",
                  "status": "completed",
                  "output": [
                    {
                      "id": "rsn_001",
                      "type": "reasoning",
                      "content": [
                        {
                          "type": "text",
                          "text": "We need to answer the status of manufacturing order MO-2026-0001. First checking status in verified facts: COMPLETED. Batches: 2."
                        }
                      ]
                    },
                    {
                      "id": "msg_002",
                      "type": "message",
                      "status": "completed",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "Manufacturing order **MO-2026-0001** is currently **COMPLETED** with 2 child batches."
                        }
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 120,
                    "output_tokens": 45,
                    "total_tokens": 165
                  }
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(groqReasoningFixture, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.generate("instructions", "What is the status of MO-2026-0001?");
        mockServer.verify();

        assertThat(resp.outputText()).isEqualTo("Manufacturing order **MO-2026-0001** is currently **COMPLETED** with 2 child batches.");
        assertThat(resp.outputText()).doesNotContain("We need to");
        assertThat(resp.outputText()).doesNotContain("reasoning");
        assertThat(resp.inputTokens()).isEqualTo(120);
        assertThat(resp.outputTokens()).isEqualTo(45);
    }

    @Test
    @DisplayName("Defect 1 Fix: Embedded <think> blocks in model output are cleanly sanitized")
    void generate_responseWithEmbeddedReasoningTags_stripsThinkTagsAndReturnsCleanFinalAnswer() {
        String embeddedThinkFixture = """
                {
                  "id": "resp_embedded_think_1",
                  "status": "completed",
                  "model": "openai/gpt-oss-20b",
                  "output": [
                    {
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "output_text",
                          "text": "<think>We need to compute planned quantity vs actual yield of both batches.\\nPlanned: 100,000.\\nActual: 49,800 + 49,750 = 99,550.</think>The planned quantity was **100,000 TABLETS**, and the total actual yield across both completed batches was **99,550 TABLETS** (99.55% yield)."
                        }
                      ]
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(embeddedThinkFixture, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.generate("instructions", "Compare planned vs actual yield");
        mockServer.verify();

        assertThat(resp.outputText()).isEqualTo("The planned quantity was **100,000 TABLETS**, and the total actual yield across both completed batches was **99,550 TABLETS** (99.55% yield).");
        assertThat(resp.outputText()).doesNotContain("<think>");
        assertThat(resp.outputText()).doesNotContain("We need to compute");
    }

    @Test
    @DisplayName("Defect 1 Fix: Provider response containing only reasoning and no final answer throws controlled INVALID_RESPONSE error")
    void generate_responseContainingOnlyReasoning_throwsControlledInvalidResponseException() {
        String onlyReasoningFixture = """
                {
                  "id": "resp_only_reasoning_1",
                  "status": "completed",
                  "model": "openai/gpt-oss-20b",
                  "output": [
                    {
                      "type": "reasoning",
                      "content": [
                        {
                          "type": "text",
                          "text": "We need to answer: Which batches are awaiting QA? Checking batches... incomplete"
                        }
                      ]
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(onlyReasoningFixture, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.generate("instructions", "Which batches are awaiting QA?"))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("no valid final answer available")
                .matches(ex -> ((LlmClientException) ex).getCategory() == LlmClientException.ErrorCategory.INVALID_RESPONSE);

        mockServer.verify();
    }

    @Test
    @DisplayName("Defect 1 Fix: Mixed content items within a single message extract only output_text and exclude reasoning parts")
    void generate_mixedContentPartsInMessage_extractsOnlyOutputTextAndExcludesReasoningParts() {
        String mixedContentFixture = """
                {
                  "id": "resp_mixed_content_1",
                  "status": "completed",
                  "model": "openai/gpt-oss-20b",
                  "output": [
                    {
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "reasoning",
                          "text": "We need to identify all batches with QA Status PENDING_QA."
                        },
                        {
                          "type": "output_text",
                          "text": "Both batch **BAT-2026-0001-01** (completed at 02:29 AM IST) and batch **BAT-2026-0001-02** (completed at 02:30 AM IST) are currently awaiting QA disposition."
                        }
                      ]
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(mixedContentFixture, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.generate("instructions", "Which batches are awaiting QA?");
        mockServer.verify();

        assertThat(resp.outputText()).isEqualTo("Both batch **BAT-2026-0001-01** (completed at 02:29 AM IST) and batch **BAT-2026-0001-02** (completed at 02:30 AM IST) are currently awaiting QA disposition.");
        assertThat(resp.outputText()).doesNotContain("We need to identify");
    }

    @Test
    @DisplayName("Groq Responses API: Response containing reasoning block in output and final answer in top-level output_text is parsed successfully")
    void generate_groqResponsesApiWithReasoningBlockAndTopLevelOutputText_parsesSuccessfully() {
        String groqFixture = """
                {
                  "id": "resp_01k3hgcytaf7vsyqqdk1932swk",
                  "object": "response",
                  "status": "completed",
                  "created_at": 1756155509,
                  "model": "openai/gpt-oss-20b",
                  "output": [
                    {
                      "type": "reasoning",
                      "id": "resp_01k3hgcytaf7vsyqqdk1932swk",
                      "status": "completed",
                      "content": [
                        {
                          "type": "reasoning_text",
                          "text": "We need to answer the status of manufacturing order MO-2026-0001. Looking at database facts..."
                        }
                      ],
                      "summary": []
                    }
                  ],
                  "output_text": "Manufacturing order **MO-2026-0001** is currently **COMPLETED**.",
                  "usage": {
                    "input_tokens": 120,
                    "output_text_tokens": 45,
                    "total_tokens": 165
                  }
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(groqFixture, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.generate("instructions", "What is the status of MO-2026-0001?");
        mockServer.verify();

        assertThat(resp.outputText()).isEqualTo("Manufacturing order **MO-2026-0001** is currently **COMPLETED**.");
        assertThat(resp.outputText()).doesNotContain("We need to answer");
        assertThat(resp.inputTokens()).isEqualTo(120);
        assertThat(resp.outputTokens()).isEqualTo(45);
        assertThat(resp.totalTokens()).isEqualTo(165);
    }

    @Test
    @DisplayName("Groq Responses API: Response with top-level output_text and no output array parses successfully")
    void generate_groqResponsesApiWithOnlyTopLevelOutputText_parsesSuccessfully() {
        String groqSimpleFixture = """
                {
                  "id": "resp_simple_01",
                  "status": "completed",
                  "model": "openai/gpt-oss-20b",
                  "output_text": "Batch yield comparison: Batch 1 yielded 98.4%, Batch 2 yielded 98.2%.",
                  "usage": {
                    "input_tokens": 50,
                    "output_tokens": 30,
                    "total_tokens": 80
                  }
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(groqSimpleFixture, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.generate("instructions", "Compare yields");
        mockServer.verify();

        assertThat(resp.outputText()).isEqualTo("Batch yield comparison: Batch 1 yielded 98.4%, Batch 2 yielded 98.2%.");
        assertThat(resp.outputTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("Groq Responses API: Response with embedded think tags in output_text strips reasoning cleanly")
    void generate_groqResponsesApiWithEmbeddedThinkTagsInOutputText_stripsThinkTagsCleanly() {
        String groqThinkTagFixture = """
                {
                  "id": "resp_think_tag_01",
                  "status": "completed",
                  "model": "openai/gpt-oss-20b",
                  "output_text": "<think>Checking order status in database records.\\nWe see MO-2026-0001 is finished.</think>Manufacturing order MO-2026-0001 is finished and verified.",
                  "usage": {
                    "input_tokens": 40,
                    "output_text_tokens": 25,
                    "total_tokens": 65
                  }
                }
                """;

        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(groqThinkTagFixture, MediaType.APPLICATION_JSON));

        LlmResponse resp = client.generate("instructions", "Order status");
        mockServer.verify();

        assertThat(resp.outputText()).isEqualTo("Manufacturing order MO-2026-0001 is finished and verified.");
        assertThat(resp.outputText()).doesNotContain("<think>");
        assertThat(resp.outputText()).doesNotContain("Checking order status");
    }

    @Test
    @DisplayName("Phase 4F.3.3.4: Three-argument generate sends custom token budgets 2048 and 6000 exactly")
    void generate_threeArgumentMethod_sendsCustomTokenBudgets() {
        String mockResponse = """
                {
                  "id": "resp_tokens_custom",
                  "status": "completed",
                  "model": "gpt-6-luna",
                  "output_text": "Response generated",
                  "usage": {
                    "input_tokens": 10,
                    "output_text_tokens": 10,
                    "total_tokens": 20
                  }
                }
                """;

        // Test 2048 tokens
        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.max_output_tokens").value(2048))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        client.generate("sys instructions", "user prompt", 2048);
        mockServer.verify();

        // Reset mock server for 6000 tokens test
        mockServer.reset();
        mockServer.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.max_output_tokens").value(6000))
                .andRespond(withSuccess(mockResponse, MediaType.APPLICATION_JSON));

        client.generate("sys instructions", "user prompt", 6000);
        mockServer.verify();
    }

    @Test
    @DisplayName("Phase 4F.3.3.4: Default configuration properties specify 60s read timeout and 3s connect timeout")
    void defaultProperties_specify60sReadTimeoutAnd3sConnectTimeout() {
        AiCopilotProperties defaultProps = new AiCopilotProperties();
        assertThat(defaultProps.getReadTimeoutMs()).isEqualTo(60000);
        assertThat(defaultProps.getConnectTimeoutMs()).isEqualTo(3000);
        assertThat(defaultProps.getMaxOutputTokens()).isEqualTo(800);
        assertThat(defaultProps.getBatchAnalysisMaxOutputTokens()).isEqualTo(4096);
    }
}
