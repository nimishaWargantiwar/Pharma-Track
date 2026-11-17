package com.pharmatrack.dto;

import com.pharmatrack.entity.CopilotRequestStatus;

/**
 * Response payload DTO returned upon message submission to an AI Copilot conversation.
 *
 * <p>Contains the persisted user inquiry and the grounded assistant response,
 * operational metadata, attributed source references, and current request status.</p>
 */
public class CopilotMessageResponseDto {

    private Long conversationId;
    private Long requestId;
    private String clientRequestId;
    private CopilotMessageDto userMessage;
    private CopilotMessageDto assistantMessage;

    public CopilotMessageResponseDto() {
    }

    public CopilotMessageResponseDto(Long conversationId, Long requestId, String clientRequestId,
                                     CopilotMessageDto userMessage, CopilotMessageDto assistantMessage) {
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.clientRequestId = clientRequestId;
        this.userMessage = userMessage;
        this.assistantMessage = assistantMessage;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public CopilotMessageDto getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(CopilotMessageDto userMessage) {
        this.userMessage = userMessage;
    }

    public CopilotMessageDto getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(CopilotMessageDto assistantMessage) {
        this.assistantMessage = assistantMessage;
    }

    public CopilotRequestStatus getStatus() {
        return assistantMessage != null ? assistantMessage.getStatus() : null;
    }

    @Override
    public String toString() {
        return "CopilotMessageResponseDto{" +
                "conversationId=" + conversationId +
                ", requestId=" + requestId +
                ", clientRequestId='" + clientRequestId + '\'' +
                ", userMessageId=" + (userMessage != null ? userMessage.getId() : null) +
                ", assistantMessageId=" + (assistantMessage != null ? assistantMessage.getId() : null) +
                ", status=" + getStatus() +
                '}';
    }
}
