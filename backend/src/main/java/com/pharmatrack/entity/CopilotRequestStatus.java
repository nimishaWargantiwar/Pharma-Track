package com.pharmatrack.entity;

/**
 * Lifecycle status of an AI Copilot inquiry request.
 */
public enum CopilotRequestStatus {
    /**
     * Request is accepted and actively being processed by the AI provider worker.
     */
    PENDING,

    /**
     * Request completed successfully with verified assistant response and citations.
     */
    COMPLETED,

    /**
     * Request failed, timed out, or was interrupted before completion.
     */
    FAILED
}
