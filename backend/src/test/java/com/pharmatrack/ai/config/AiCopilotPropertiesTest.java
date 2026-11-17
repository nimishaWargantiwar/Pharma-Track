package com.pharmatrack.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI Copilot Configuration Properties & Startup Validation Tests")
class AiCopilotPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(AiCopilotProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("Default configuration keeps AI disabled with 60s read timeout and safe defaults")
    void defaultConfiguration_keepsAiDisabledWithSafeDefaults() {
        AiCopilotProperties properties = new AiCopilotProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getProvider()).isEqualTo("openai");
        assertThat(properties.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(properties.getApiKey()).isNull();
        assertThat(properties.getModel()).isEqualTo("gpt-6-luna");
        assertThat(properties.getConnectTimeoutMs()).isEqualTo(3000);
        assertThat(properties.getReadTimeoutMs()).isEqualTo(60000);
        assertThat(properties.getMaxOutputTokens()).isEqualTo(800);
        assertThat(properties.getBatchAnalysisMaxOutputTokens()).isEqualTo(4096);

        // Validation must succeed cleanly when disabled even if apiKey is missing
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Mutators apply trimmed and normalized values with safe fallbacks")
    void mutators_applyNormalizedValues() {
        AiCopilotProperties properties = new AiCopilotProperties();

        properties.setEnabled(true);
        properties.setProvider("  openai  ");
        properties.setBaseUrl("  https://custom.openai.azure.com/v1/  ");
        properties.setApiKey("  sk-test-key-12345  ");
        properties.setModel("  gpt-6-luna  ");
        properties.setConnectTimeoutMs(5000);
        properties.setReadTimeoutMs(45000);
        properties.setMaxOutputTokens(1200);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getProvider()).isEqualTo("openai");
        assertThat(properties.getBaseUrl()).isEqualTo("https://custom.openai.azure.com/v1/");
        assertThat(properties.getApiKey()).isEqualTo("sk-test-key-12345");
        assertThat(properties.getModel()).isEqualTo("gpt-6-luna");
        assertThat(properties.getConnectTimeoutMs()).isEqualTo(5000);
        assertThat(properties.getReadTimeoutMs()).isEqualTo(45000);
        assertThat(properties.getMaxOutputTokens()).isEqualTo(1200);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Negative timeouts and token limits fallback to positive defaults")
    void negativeValues_fallbackToDefaults() {
        AiCopilotProperties properties = new AiCopilotProperties();

        properties.setConnectTimeoutMs(-100);
        properties.setReadTimeoutMs(0);
        properties.setMaxOutputTokens(-50);

        assertThat(properties.getConnectTimeoutMs()).isEqualTo(3000);
        assertThat(properties.getReadTimeoutMs()).isEqualTo(60000);
        assertThat(properties.getMaxOutputTokens()).isEqualTo(800);
    }

    @Test
    @DisplayName("Base URL normalization trims whitespace and strips trailing slashes")
    void normalizeBaseUrl_stripsTrailingSlashes() {
        assertThat(AiCopilotProperties.normalizeBaseUrl("https://api.openai.com/v1"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(AiCopilotProperties.normalizeBaseUrl("https://api.openai.com/v1/"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(AiCopilotProperties.normalizeBaseUrl("https://api.openai.com/v1///"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(AiCopilotProperties.normalizeBaseUrl("   https://gateway.internal.corp/ai/v1/  "))
                .isEqualTo("https://gateway.internal.corp/ai/v1");
        assertThat(AiCopilotProperties.normalizeBaseUrl("   ")).isNull();
        assertThat(AiCopilotProperties.normalizeBaseUrl(null)).isNull();
    }

    @Test
    @DisplayName("Validation fails closed if base URL misses /v1 or contains trailing endpoint")
    void validate_failsClosedWhenBaseUrlViolatesContract() {
        AiCopilotProperties properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-valid-key");

        // Missing /v1
        properties.setBaseUrl("https://api.openai.com");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.base-url must be a valid absolute HTTP/HTTPS URL ending with '/v1'")
                .hasMessageContaining("Expected format: https://api.openai.com/v1.");

        // Wrong version
        properties.setBaseUrl("https://api.openai.com/v2");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.base-url must be a valid absolute HTTP/HTTPS URL ending with '/v1'")
                .hasMessageContaining("Expected format: https://api.openai.com/v1.");

        // Trailing endpoint included
        properties.setBaseUrl("https://api.openai.com/v1/responses");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.base-url must be a valid absolute HTTP/HTTPS URL ending with '/v1'")
                .hasMessageContaining("Expected format: https://api.openai.com/v1.");

        // Valid custom proxy with /v1 succeeds
        properties.setBaseUrl("https://gateway.internal.corp/ai/v1/");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Security regression: invalid URL containing username and password fails without leaking credentials")
    void validate_urlWithUserInfo_failsWithoutExposingCredentials() {
        AiCopilotProperties properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-valid-key-99999");
        properties.setBaseUrl("https://admin:secret123@proxy.internal/v2");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).doesNotContain("admin");
                    assertThat(msg).doesNotContain("secret123");
                    assertThat(msg).doesNotContain("@");
                    assertThat(msg).doesNotContain("proxy.internal");
                    assertThat(msg).contains("app.ai.base-url must be a valid absolute HTTP/HTTPS URL ending with '/v1'");
                    assertThat(msg).contains("Expected format: https://api.openai.com/v1.");
                });
    }

    @Test
    @DisplayName("Security regression: invalid URL containing query token fails without leaking query parameters")
    void validate_urlWithQueryToken_failsWithoutExposingQueryParamOrToken() {
        AiCopilotProperties properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-valid-key-99999");
        properties.setBaseUrl("https://proxy.internal/v2?api_key=token123");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).doesNotContain("token123");
                    assertThat(msg).doesNotContain("api_key");
                    assertThat(msg).doesNotContain("?");
                    assertThat(msg).doesNotContain("proxy.internal");
                    assertThat(msg).contains("app.ai.base-url must be a valid absolute HTTP/HTTPS URL ending with '/v1'");
                    assertThat(msg).contains("Expected format: https://api.openai.com/v1.");
                });
    }

    @Test
    @DisplayName("Security regression: invalid URL containing sensitive fragment fails without leaking fragment")
    void validate_urlWithFragment_failsWithoutExposingFragment() {
        AiCopilotProperties properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-valid-key-99999");
        properties.setBaseUrl("https://proxy.internal/v2#secret-fragment");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).doesNotContain("secret-fragment");
                    assertThat(msg).doesNotContain("#");
                    assertThat(msg).doesNotContain("proxy.internal");
                    assertThat(msg).contains("app.ai.base-url must be a valid absolute HTTP/HTTPS URL ending with '/v1'");
                    assertThat(msg).contains("Expected format: https://api.openai.com/v1.");
                });
    }

    @Test
    @DisplayName("Validation fails closed if AI is enabled but API key is missing or blank")
    void validate_failsClosedWhenApiKeyMissing() {
        AiCopilotProperties properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey(null);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI Copilot configuration error")
                .hasMessageContaining("app.ai.api-key");

        properties.setApiKey("   ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.api-key");
    }

    @Test
    @DisplayName("Validation fails closed if AI is enabled but baseUrl or model is blank")
    void validate_failsClosedWhenBaseUrlOrModelBlank() {
        AiCopilotProperties properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-valid-key");

        properties.setBaseUrl("");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.base-url");

        properties.setBaseUrl("https://api.openai.com/v1");
        properties.setModel("   ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.model");
    }

    @Test
    @DisplayName("toString never exposes raw API key secret")
    void toString_masksApiKeySecret() {
        AiCopilotProperties properties = new AiCopilotProperties();
        String secretKey = "sk-proj-super-secret-enterprise-key-99999";
        properties.setApiKey(secretKey);

        String representation = properties.toString();

        assertThat(representation).doesNotContain(secretKey);
        assertThat(representation).contains("[PROTECTED");
        assertThat(representation).contains("length=" + secretKey.length());
    }

    @Test
    @DisplayName("Spring startup test 1: AI disabled, no API key -> context starts successfully")
    void springStartup_aiDisabled_noApiKey_contextStarts() {
        contextRunner
                .withPropertyValues("app.ai.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiCopilotProperties.class);
                    AiCopilotProperties props = context.getBean(AiCopilotProperties.class);
                    assertThat(props.isEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("Spring startup test 2: AI enabled, missing API key -> context fails to start")
    void springStartup_aiEnabled_missingApiKey_contextFails() {
        contextRunner
                .withPropertyValues("app.ai.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("AI Copilot configuration error: app.ai.api-key / AI_API_KEY must be provided when app.ai.enabled is true.");
                });
    }

    @Test
    @DisplayName("Spring startup test 3: AI enabled, valid API key and configuration -> context starts successfully")
    void springStartup_aiEnabled_validConfiguration_contextStarts() {
        contextRunner
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.api-key=sk-test-valid-key-12345",
                        "app.ai.model=gpt-6-luna",
                        "app.ai.base-url=https://api.openai.com/v1",
                        "app.ai.read-timeout-ms=60000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiCopilotProperties.class);
                    AiCopilotProperties props = context.getBean(AiCopilotProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getApiKey()).isEqualTo("sk-test-valid-key-12345");
                    assertThat(props.getReadTimeoutMs()).isEqualTo(60000);
                });
    }

    @Test
    @DisplayName("Phase 4F.3.3.4: Batch Analysis budget defaults to 4096 and Copilot remains 800")
    void batchAnalysisBudget_defaultsTo4096_copilotRemains800() {
        AiCopilotProperties properties = new AiCopilotProperties();
        assertThat(properties.getBatchAnalysisMaxOutputTokens()).isEqualTo(4096);
        assertThat(properties.getMaxOutputTokens()).isEqualTo(800);
    }

    @Test
    @DisplayName("Phase 4F.3.3.4: Custom Batch Analysis budgets 2048 and 6000 are preserved and do not alter Copilot budget")
    void customBatchAnalysisBudgets_arePreservedAndDoNotAlterCopilot() {
        AiCopilotProperties properties = new AiCopilotProperties();

        properties.setBatchAnalysisMaxOutputTokens(2048);
        assertThat(properties.getBatchAnalysisMaxOutputTokens()).isEqualTo(2048);
        assertThat(properties.getMaxOutputTokens()).isEqualTo(800);

        properties.setBatchAnalysisMaxOutputTokens(6000);
        assertThat(properties.getBatchAnalysisMaxOutputTokens()).isEqualTo(6000);
        assertThat(properties.getMaxOutputTokens()).isEqualTo(800);
    }

    @Test
    @DisplayName("Phase 4F.3.3.4: Zero Batch Analysis budget fails startup when AI is enabled")
    void zeroBatchAnalysisBudget_failsStartupWhenEnabled() {
        AiCopilotProperties properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test-key-12345");
        properties.setBatchAnalysisMaxOutputTokens(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.batch-analysis.max-output-tokens must be a positive integer greater than 0");
    }

    @Test
    @DisplayName("Phase 4F.3.3.4: Negative Batch Analysis budget fails startup when AI is enabled")
    void negativeBatchAnalysisBudget_failsStartupWhenEnabled() {
        AiCopilotProperties properties = new AiCopilotProperties();
        properties.setEnabled(true);
        properties.setApiKey("sk-test-key-12345");
        properties.setBatchAnalysisMaxOutputTokens(-500);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai.batch-analysis.max-output-tokens must be a positive integer greater than 0");
    }

    @Test
    @DisplayName("Phase 4F.3.3.4: Spring context binds app.ai.batch-analysis.max-output-tokens correctly")
    void springStartup_bindsBatchAnalysisMaxOutputTokens() {
        contextRunner
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.api-key=sk-test-valid-key-12345",
                        "app.ai.model=gpt-6-luna",
                        "app.ai.base-url=https://api.openai.com/v1",
                        "app.ai.batch-analysis.max-output-tokens=2048"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiCopilotProperties props = context.getBean(AiCopilotProperties.class);
                    assertThat(props.getBatchAnalysisMaxOutputTokens()).isEqualTo(2048);
                    assertThat(props.getMaxOutputTokens()).isEqualTo(800);
                });
    }

    @Test
    @DisplayName("Phase 4F.3.3.4: Spring context fails startup if batch-analysis.max-output-tokens is zero or negative")
    void springStartup_failsWhenBatchAnalysisMaxOutputTokensIsInvalid() {
        contextRunner
                .withPropertyValues(
                        "app.ai.enabled=true",
                        "app.ai.api-key=sk-test-valid-key-12345",
                        "app.ai.model=gpt-6-luna",
                        "app.ai.base-url=https://api.openai.com/v1",
                        "app.ai.batch-analysis.max-output-tokens=0"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("AI Copilot configuration error: app.ai.batch-analysis.max-output-tokens must be a positive integer greater than 0 (found: 0).");
                });
    }
}
