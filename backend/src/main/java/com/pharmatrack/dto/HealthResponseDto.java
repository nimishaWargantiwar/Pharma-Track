package com.pharmatrack.dto;

import com.pharmatrack.util.AppTime;

public class HealthResponseDto {

    private String status;
    private String message;
    private String timestamp;

    public HealthResponseDto() {
        this.timestamp = AppTime.nowIsoOffsetString();
    }

    public HealthResponseDto(String status, String message) {
        this.status = status;
        this.message = message;
        this.timestamp = AppTime.nowIsoOffsetString();
    }

    public HealthResponseDto(String status, String message, String timestamp) {
        this.status = status;
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}

