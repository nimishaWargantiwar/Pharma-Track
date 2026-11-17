package com.pharmatrack.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the PharmaTrack AI Copilot integration.
 *
 * <p>Prefix: {@code app.ai}
 *
 * <p>Security & Safety:
 * <ul>
 *   <li>The API key has NO hardcoded default and is populated strictly via environment variables (e.g. {@code AI_API_KEY}).</li>
 *   <li>The API key is never exposed in {@link #toString()} or serialized into logs.</li>
 *   <li>When {@code enabled} is {@code false} (default), missing API keys are completely valid.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiCopilotProperties {

    /**
     * Master toggle enabling AI Copilot functionality. Defaults to false.
     */
    private boolean enabled = false;

    /**
     * AI provider identifier (e.g. "openai").
     */
    private String provider = "openai";

    /**
     * Base URL for the provider API. Defaults to OpenAI v1 endpoint.
     */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * Provider authentication secret (API key).
     * Must be supplied via environment variable or secure secrets manager. Never hardcoded.
     */
    private String apiKey;

    /**
     * Model identifier to invoke. Defaults to gpt-6-luna.
     */
    private String model = "gpt-6-luna";

    /**
     * HTTP connection timeout in milliseconds.
     */
    private int connectTimeoutMs = 3000;

    /**
     * HTTP read/response timeout in milliseconds. Defaults to 60,000 ms (60 seconds).
     * Note: This is a socket-read inactivity timeout (SO_TIMEOUT), not a guaranteed total request deadline.
     */
    private int readTimeoutMs = 60000;

    /**
     * Maximum output tokens to generate per completion request for AI Copilot. Defaults to 800.
     */
    private int maxOutputTokens = 800;

    /**
     * Dedicated configuration for AI Batch Analysis.
     */
    private BatchAnalysis batchAnalysis = new BatchAnalysis();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider != null ? provider.trim() : null;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl.trim() : null;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null && !apiKey.isBlank() ? apiKey.trim() : null;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model != null ? model.trim() : null;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 3000;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 60000;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : 800;
    }

    public BatchAnalysis getBatchAnalysis() {
        return batchAnalysis;
    }

    public void setBatchAnalysis(BatchAnalysis batchAnalysis) {
        this.batchAnalysis = batchAnalysis != null ? batchAnalysis : new BatchAnalysis();
    }

    public int getBatchAnalysisMaxOutputTokens() {
        return batchAnalysis != null ? batchAnalysis.getMaxOutputTokens() : 4096;
    }

    public void setBatchAnalysisMaxOutputTokens(int batchAnalysisMaxOutputTokens) {
        if (this.batchAnalysis == null) {
            this.batchAnalysis = new BatchAnalysis();
        }
        this.batchAnalysis.setMaxOutputTokens(batchAnalysisMaxOutputTokens);
    }

    public static class BatchAnalysis {
        /**
         * Maximum output tokens to generate for Batch Analysis Reports. Defaults to 4096.
         */
        private int maxOutputTokens = 4096;

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    /**
     * Normalizes a configured base URL by trimming whitespace and stripping trailing slashes.
     *
     * @param url raw base URL string
     * @return normalized URL without trailing slashes, or null if null/blank
     */
    public static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Validates configuration when AI Copilot is actively enabled.
     * Invoked automatically by Spring container during post-construction lifecycle.
     *
     * @throws IllegalStateException if enabled is true but required properties are invalid
     */
    @jakarta.annotation.PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "AI Copilot configuration error: app.ai.api-key / AI_API_KEY must be provided when app.ai.enabled is true.");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "AI Copilot configuration error: app.ai.base-url must not be blank when enabled.");
        }
        String normalizedBase = normalizeBaseUrl(baseUrl);
        if (normalizedBase == null || !isValidBaseUrl(normalizedBase)) {
            throw new IllegalStateException(
                    "AI Copilot configuration error: app.ai.base-url must be a valid absolute HTTP/HTTPS URL ending with '/v1'. Expected format: https://api.openai.com/v1.");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "AI Copilot configuration error: app.ai.model must not be blank when enabled.");
        }
        if (getBatchAnalysisMaxOutputTokens() <= 0) {
            throw new IllegalStateException(
                    "AI Copilot configuration error: app.ai.batch-analysis.max-output-tokens must be a positive integer greater than 0 (found: "
                            + getBatchAnalysisMaxOutputTokens() + ").");
        }
    }

    private static boolean isValidBaseUrl(String normalized) {
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return false;
        }
        if (!normalized.endsWith("/v1")) {
            return false;
        }
        return !normalized.contains("@") && !normalized.contains("?") && !normalized.contains("#");
    }

    @Override
    public String toString() {
        return "AiCopilotProperties{" +
                "enabled=" + enabled +
                ", provider='" + provider + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey=" + (apiKey != null ? "[PROTECTED, length=" + apiKey.length() + "]" : "[NOT CONFIGURED]") +
                ", model='" + model + '\'' +
                ", connectTimeoutMs=" + connectTimeoutMs +
                ", readTimeoutMs=" + readTimeoutMs +
                ", maxOutputTokens=" + maxOutputTokens +
                ", batchAnalysisMaxOutputTokens=" + getBatchAnalysisMaxOutputTokens() +
                '}';
    }
}
