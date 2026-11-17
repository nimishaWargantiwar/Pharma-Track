package com.pharmatrack.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.ai.config.AiCopilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Production implementation of {@link LlmClient} integrating with the OpenAI Responses API
 * ({@code POST /v1/responses}) using Spring Framework's {@link RestClient}.
 *
 * <p>Architectural guarantees:
 * <ul>
 *   <li>Communicates exclusively via backend REST calls; browser clients never access the AI provider.</li>
 *   <li>Enforces Bearer authentication using backend-only environment secrets.</li>
 *   <li>Sets {@code store: false} to prohibit cloud provider training or data retention on regulated MES records.</li>
 *   <li>Parses OpenAI Responses API schema (extracting {@code output} message text, token usage, and response ID).</li>
 *   <li>Sanitizes all exceptions to prevent leaking API keys, internal endpoints, or raw provider errors.</li>
 * </ul>
 */
@Service
public class OpenAiLlmClient implements LlmClient {

    private static final Set<String> REASONING_ITEM_TYPES = Set.of(
            "reasoning", "thought", "thinking", "analysis", "internal"
    );

    private static final Pattern EMBEDDED_REASONING_BLOCK_PATTERN =
            Pattern.compile("(?s)<(think|thought|reasoning)>.*?</\\1>", Pattern.CASE_INSENSITIVE);

    private static final Pattern UNCLOSED_REASONING_BLOCK_PATTERN =
            Pattern.compile("(?s)^\\s*<(think|thought|reasoning)>.*$", Pattern.CASE_INSENSITIVE);

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final AiCopilotProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public OpenAiLlmClient(AiCopilotProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, createDefaultRestClientBuilder(properties).build());
    }

    public OpenAiLlmClient(AiCopilotProperties properties, ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this(properties, objectMapper, restClientBuilder.build());
    }

    public OpenAiLlmClient(AiCopilotProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        if (properties == null) {
            throw new IllegalArgumentException("AiCopilotProperties must not be null");
        }
        this.properties = properties;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.restClient = restClient != null ? restClient : createDefaultRestClientBuilder(properties).build();
    }

    private static RestClient.Builder createDefaultRestClientBuilder(AiCopilotProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        String rawBaseUrl = properties.getBaseUrl();
        String cleanBaseUrl = AiCopilotProperties.normalizeBaseUrl(rawBaseUrl);
        if (cleanBaseUrl == null || cleanBaseUrl.isBlank()) {
            cleanBaseUrl = "https://api.openai.com/v1";
        }

        return RestClient.builder()
                .baseUrl(cleanBaseUrl)
                .requestFactory(requestFactory);
    }

    @Override
    public LlmResponse generate(String instructions, String input) {
        return generate(instructions, input, properties.getMaxOutputTokens());
    }

    @Override
    public LlmResponse generate(String instructions, String input, int maxOutputTokens) {
        if (!properties.isEnabled()) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.CONFIGURATION_ERROR,
                    "PharmaTrack AI Copilot is currently disabled in application configuration."
            );
        }

        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.CONFIGURATION_ERROR,
                    "AI provider API key is not configured. Set AI_API_KEY environment variable or app.ai.api-key."
            );
        }

        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Prompt input must not be blank");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", properties.getModel());
        if (instructions != null && !instructions.isBlank()) {
            requestBody.put("instructions", instructions.trim());
        }
        requestBody.put("input", input.trim());
        requestBody.put("store", false);
        int effectiveTokens = maxOutputTokens > 0 ? maxOutputTokens : properties.getMaxOutputTokens();
        if (effectiveTokens > 0) {
            requestBody.put("max_output_tokens", effectiveTokens);
        }

        log.debug("Dispatching OpenAI Responses API call model={} instructionsPresent={} inputLength={} maxTokens={}",
                properties.getModel(), instructions != null && !instructions.isBlank(), input.trim().length(), effectiveTokens);

        ResponseEntity<String> response;
        try {
            response = restClient.post()
                    .uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);
        } catch (HttpClientErrorException.Unauthorized ex) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.UNAUTHORIZED,
                    401,
                    "AI provider authentication failed. Please verify API key configuration.",
                    ex
            );
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.FORBIDDEN,
                    403,
                    "AI provider access forbidden. Check account permissions or API restrictions.",
                    ex
            );
        } catch (HttpClientErrorException.BadRequest ex) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.BAD_REQUEST,
                    400,
                    "AI provider rejected the request due to invalid parameters.",
                    ex
            );
        } catch (HttpClientErrorException.TooManyRequests ex) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.RATE_LIMITED,
                    429,
                    "AI provider rate limit reached or quota exhausted. Please try again later.",
                    ex
            );
        } catch (HttpClientErrorException ex) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.BAD_REQUEST,
                    ex.getStatusCode().value(),
                    "AI provider rejected request with client error (status " + ex.getStatusCode().value() + ").",
                    ex
            );
        } catch (HttpServerErrorException ex) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.SERVER_ERROR,
                    ex.getStatusCode().value(),
                    "AI provider service is temporarily unavailable (status " + ex.getStatusCode().value() + "). Please retry later.",
                    ex
            );
        } catch (ResourceAccessException ex) {
            if (isTimeout(ex)) {
                throw new LlmClientException(
                        LlmClientException.ErrorCategory.TIMEOUT,
                        "AI provider request timed out after configured threshold.",
                        ex
                );
            }
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.CONNECTION_FAILURE,
                    "Unable to establish connection to AI provider at configured base URL.",
                    ex
            );
        } catch (RestClientException ex) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.SERVER_ERROR,
                    "Failed to communicate with AI provider.",
                    ex
            );
        }

        return parseResponsesPayload(response.getBody());
    }

    private LlmResponse parseResponsesPayload(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.INVALID_RESPONSE,
                    "AI provider returned an empty HTTP response body"
            );
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.INVALID_RESPONSE,
                    "Failed to parse AI provider JSON response payload",
                    ex
            );
        }

        if (root == null || !root.isObject()) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.INVALID_RESPONSE,
                    "AI provider returned an invalid non-object JSON payload"
            );
        }

        if (root.hasNonNull("error")) {
            JsonNode errorNode = root.get("error");
            String errMsg = errorNode.hasNonNull("message")
                    ? errorNode.get("message").asText()
                    : "Provider returned error status";
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.SERVER_ERROR,
                    "AI provider returned an error: " + errMsg
            );
        }

        if (!root.hasNonNull("status")) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.INVALID_RESPONSE,
                    "AI provider response is missing required 'status' attribute."
            );
        }

        String status = root.get("status").asText();
        if ("failed".equalsIgnoreCase(status)) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.SERVER_ERROR,
                    "AI provider execution terminated with status 'failed'."
            );
        }

        if ("incomplete".equalsIgnoreCase(status)) {
            String reason = "unspecified";
            if (root.hasNonNull("incomplete_details") && root.get("incomplete_details").hasNonNull("reason")) {
                reason = root.get("incomplete_details").get("reason").asText();
            }
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.INVALID_RESPONSE,
                    "AI provider returned incomplete output (reason: " + reason + ")."
            );
        }

        if (!"completed".equalsIgnoreCase(status)) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.INVALID_RESPONSE,
                    "AI provider returned unexpected completion status: '" + status + "'."
            );
        }

        String responseId = root.hasNonNull("id") ? root.get("id").asText() : null;
        String model = root.hasNonNull("model") ? root.get("model").asText() : properties.getModel();

        StringBuilder textBuilder = new StringBuilder();

        JsonNode outputNode = root.get("output");
        if (outputNode != null && outputNode.isArray()) {
            for (JsonNode item : outputNode) {
                // Programmatically filter out non-user-facing analysis/reasoning items from Groq / Responses API
                if (isReasoningItem(item)) {
                    log.debug("Skipping internal reasoning item in Responses API payload type={}",
                            item.hasNonNull("type") ? item.get("type").asText() : "unspecified");
                    continue;
                }

                JsonNode contentNode = item.get("content");
                if (contentNode != null && contentNode.isArray()) {
                    for (JsonNode contentItem : contentNode) {
                        if (isReasoningContentItem(contentItem)) {
                            continue;
                        }
                        if (contentItem.hasNonNull("text")) {
                            textBuilder.append(contentItem.get("text").asText());
                        } else if (contentItem.hasNonNull("output_text")) {
                            textBuilder.append(contentItem.get("output_text").asText());
                        }
                    }
                } else if (contentNode != null && contentNode.isTextual()) {
                    textBuilder.append(contentNode.asText());
                } else if (item.hasNonNull("text")) {
                    textBuilder.append(item.get("text").asText());
                } else if (item.hasNonNull("output_text")) {
                    textBuilder.append(item.get("output_text").asText());
                }
            }
        }

        // Top-level fallback: Groq and OpenAI Responses API expose the primary generated text via 'output_text'
        if (textBuilder.isEmpty()) {
            if (root.hasNonNull("output_text")) {
                textBuilder.append(root.get("output_text").asText());
            } else if (root.hasNonNull("text")) {
                textBuilder.append(root.get("text").asText());
            }
        }

        String rawOutput = textBuilder.toString();
        String cleanOutput = sanitizeFinalAnswer(rawOutput);

        if (cleanOutput.isEmpty()) {
            throw new LlmClientException(
                    LlmClientException.ErrorCategory.INVALID_RESPONSE,
                    "AI provider returned an empty or missing output text response (no valid final answer available)."
            );
        }

        int inputTokens = 0;
        int outputTokens = 0;
        int totalTokens = 0;

        JsonNode usageNode = root.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            if (usageNode.hasNonNull("input_tokens")) {
                inputTokens = usageNode.get("input_tokens").asInt(0);
            }
            if (usageNode.hasNonNull("output_tokens")) {
                outputTokens = usageNode.get("output_tokens").asInt(0);
            } else if (usageNode.hasNonNull("output_text_tokens")) {
                outputTokens = usageNode.get("output_text_tokens").asInt(0);
            }
            if (usageNode.hasNonNull("total_tokens")) {
                totalTokens = usageNode.get("total_tokens").asInt(0);
            } else {
                totalTokens = inputTokens + outputTokens;
            }
        }

        log.debug("AI Responses API completed id={} model={} inputTokens={} outputTokens={}",
                responseId, model, inputTokens, outputTokens);

        return new LlmResponse(cleanOutput, model, responseId, inputTokens, outputTokens, totalTokens);
    }

    static boolean isReasoningItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            return false;
        }
        if (item.hasNonNull("type")) {
            String type = item.get("type").asText().toLowerCase(Locale.ROOT).trim();
            if (REASONING_ITEM_TYPES.contains(type)) {
                return true;
            }
        }
        if (item.hasNonNull("role")) {
            String role = item.get("role").asText().toLowerCase(Locale.ROOT).trim();
            if (REASONING_ITEM_TYPES.contains(role)) {
                return true;
            }
        }
        return false;
    }

    static boolean isReasoningContentItem(JsonNode contentItem) {
        if (contentItem == null || !contentItem.isObject()) {
            return false;
        }
        if (contentItem.hasNonNull("type")) {
            String type = contentItem.get("type").asText().toLowerCase(Locale.ROOT).trim();
            if (REASONING_ITEM_TYPES.contains(type)) {
                return true;
            }
        }
        return false;
    }

    static String sanitizeFinalAnswer(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = EMBEDDED_REASONING_BLOCK_PATTERN.matcher(text).replaceAll("");
        sanitized = UNCLOSED_REASONING_BLOCK_PATTERN.matcher(sanitized).replaceAll("");
        return sanitized.trim();
    }

    private static boolean isTimeout(ResourceAccessException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().toLowerCase().contains("timed out")) {
                return true;
            }
            cause = cause.getCause();
        }
        return ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out");
    }

    @Override
    public boolean isConfigured() {
        return properties.isEnabled() && properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    @Override
    public String getModel() {
        return properties.getModel();
    }

    @Override
    public String getProvider() {
        return properties.getProvider();
    }
}
