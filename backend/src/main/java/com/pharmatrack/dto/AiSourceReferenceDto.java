package com.pharmatrack.dto;

/**
 * DTO representing an attributed PharmaTrack database record cited by the AI Copilot.
 *
 * <p>Enables the frontend to distinguish retrieved factual system records from
 * generative text and render interactive deep-link navigation pills.</p>
 */
public class AiSourceReferenceDto {

    private String recordType;
    private String recordIdentifier;
    private String title;
    private String module;
    private String summary;

    public AiSourceReferenceDto() {
    }

    public AiSourceReferenceDto(String recordType, String recordIdentifier, String title, String module) {
        this(recordType, recordIdentifier, title, module, null);
    }

    public AiSourceReferenceDto(String recordType, String recordIdentifier, String title, String module, String summary) {
        this.recordType = recordType;
        this.recordIdentifier = recordIdentifier;
        this.title = title;
        this.module = module;
        this.summary = summary;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public String getRecordIdentifier() {
        return recordIdentifier;
    }

    public void setRecordIdentifier(String recordIdentifier) {
        this.recordIdentifier = recordIdentifier;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    @Override
    public String toString() {
        return "AiSourceReferenceDto{" +
                "recordType='" + recordType + '\'' +
                ", recordIdentifier='" + recordIdentifier + '\'' +
                ", title='" + title + '\'' +
                ", module='" + module + '\'' +
                '}';
    }
}
