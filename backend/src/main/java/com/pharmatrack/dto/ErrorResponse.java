package com.pharmatrack.dto;

import com.pharmatrack.util.AppTime;

import java.util.Map;

/**
 * Standard API error response payload.
 */
public class ErrorResponse {

    private int status;
    private String error;
    private String message;
    private String errorCode;
    private String timestamp;
    private Map<String, String> validationErrors;

    public ErrorResponse() {
        this.timestamp = AppTime.nowIsoOffsetString();
    }

    public ErrorResponse(int status, String error, String message) {
        this(status, error, message, null, null);
    }

    public ErrorResponse(int status, String error, String message, String errorCode) {
        this(status, error, message, errorCode, null);
    }

    public ErrorResponse(int status, String error, String message, Map<String, String> validationErrors) {
        this(status, error, message, null, validationErrors);
    }

    public ErrorResponse(int status, String error, String message, String errorCode, Map<String, String> validationErrors) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.errorCode = errorCode;
        this.timestamp = AppTime.nowIsoOffsetString();
        this.validationErrors = validationErrors;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, String> getValidationErrors() {
        return validationErrors;
    }

    public void setValidationErrors(Map<String, String> validationErrors) {
        this.validationErrors = validationErrors;
    }
}
