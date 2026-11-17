package com.pharmatrack.dto;

import com.pharmatrack.util.AppTime;

import java.util.ArrayList;
import java.util.List;

/**
 * Response payload DTO returned by PharmaTrack AI Copilot.
 *
 * <p>Contains the advisory response grounded in retrieved MES records, verified citations,
 * operational metadata, and a life-sciences advisory disclaimer.</p>
 */
public class AiQueryResponseDto {

    public static final String DEFAULT_DISCLAIMER =
            "Advisory MES Copilot guidance based exclusively on verified PharmaTrack database records. " +
            "Not an automated manufacturing instruction, equipment override, or QA release disposition.";

    private String answer;
    private String contextType;
    private String contextIdentifier;
    private List<AiSourceReferenceDto> sources = new ArrayList<>();
    private String model;
    private String timestamp;
    private String disclaimer;

    public AiQueryResponseDto() {
        this.timestamp = AppTime.nowIsoOffsetString();
        this.disclaimer = DEFAULT_DISCLAIMER;
    }

    public AiQueryResponseDto(String answer, String model) {
        this();
        this.answer = answer;
        this.model = model;
    }

    public AiQueryResponseDto(String answer, String contextType, String contextIdentifier,
                              List<AiSourceReferenceDto> sources, String model) {
        this();
        this.answer = answer;
        this.contextType = contextType;
        this.contextIdentifier = contextIdentifier;
        if (sources != null) {
            this.sources = sources;
        }
        this.model = model;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getContextType() {
        return contextType;
    }

    public void setContextType(String contextType) {
        this.contextType = contextType;
    }

    public String getContextIdentifier() {
        return contextIdentifier;
    }

    public void setContextIdentifier(String contextIdentifier) {
        this.contextIdentifier = contextIdentifier;
    }

    public List<AiSourceReferenceDto> getSources() {
        return sources;
    }

    public void setSources(List<AiSourceReferenceDto> sources) {
        this.sources = sources != null ? sources : new ArrayList<>();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getDisclaimer() {
        return disclaimer;
    }

    public void setDisclaimer(String disclaimer) {
        this.disclaimer = disclaimer;
    }

    @Override
    public String toString() {
        return "AiQueryResponseDto{" +
                "contextType='" + contextType + '\'' +
                ", contextIdentifier='" + contextIdentifier + '\'' +
                ", sourcesCount=" + (sources != null ? sources.size() : 0) +
                ", model='" + model + '\'' +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
