package com.pharmatrack.ai.client;

/**
 * Provider-agnostic client interface for invoking foundational Large Language Models (LLMs).
 *
 * <p>Decouples higher-level MES context assembly and prompt orchestration from concrete
 * vendor HTTP wire protocols and client SDKs.</p>
 */
public interface LlmClient {

    /**
     * Generate an AI completion using system instructions and user input.
     *
     * @param instructions high-level system instructions governing behavior and constraints
     * @param input        user prompt or context payload
     * @return structured LLM response including generated text, token usage, and request metadata
     * @throws LlmClientException if the provider fails, returns an error status, times out, or produces invalid output
     */
    LlmResponse generate(String instructions, String input);

    /**
     * Generate an AI completion with an explicit maximum output token override.
     *
     * @param instructions    high-level system instructions governing behavior and constraints
     * @param input           user prompt or context payload
     * @param maxOutputTokens explicit maximum token generation threshold
     * @return structured LLM response including generated text, token usage, and request metadata
     * @throws LlmClientException if the provider fails, returns an error status, times out, or produces invalid output
     */
    default LlmResponse generate(String instructions, String input, int maxOutputTokens) {
        return generate(instructions, input);
    }

    /**
     * Check if the client is properly configured and operational.
     *
     * @return true if enabled and an API key is available
     */
    boolean isConfigured();

    /**
     * Get the configured model name.
     *
     * @return model identifier string
     */
    String getModel();

    /**
     * Get the provider identifier.
     *
     * @return provider string (e.g. "openai")
     */
    String getProvider();
}
