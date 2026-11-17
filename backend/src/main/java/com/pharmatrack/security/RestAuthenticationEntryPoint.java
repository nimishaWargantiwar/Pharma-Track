package com.pharmatrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.ErrorResponse;
import com.pharmatrack.exception.SensitiveDataSanitizer;
import com.pharmatrack.util.AppTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Returns consistent JSON {@link ErrorResponse} payloads with HTTP 401 for unauthenticated requests
 * or invalid, expired, revoked, or disabled-account JWTs.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String AUTH_ERROR_MESSAGE_ATTR = "pharmatrack.auth.error.message";
    public static final String AUTH_ERROR_CODE_ATTR = "pharmatrack.auth.error.code";
    private static final String DEFAULT_MESSAGE = SensitiveDataSanitizer.DEFAULT_AUTHENTICATION_REQUIRED_MESSAGE;

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : AppTime.createObjectMapper();
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Object attrMessage = request.getAttribute(AUTH_ERROR_MESSAGE_ATTR);
        String message = attrMessage instanceof String str
                ? SensitiveDataSanitizer.sanitizeClientAuthText(str, DEFAULT_MESSAGE)
                : DEFAULT_MESSAGE;

        Object attrCode = request.getAttribute(AUTH_ERROR_CODE_ATTR);
        String errorCode;
        if (attrCode instanceof String codeStr && !codeStr.isBlank()) {
            errorCode = codeStr;
        } else if (authException instanceof JwtAuthenticationException jwtEx) {
            errorCode = jwtEx.getErrorCode();
        } else if (authException instanceof DisabledException) {
            errorCode = JwtAuthenticationException.CODE_ACCOUNT_DISABLED;
        } else {
            errorCode = JwtAuthenticationException.CODE_AUTHENTICATION_REQUIRED;
        }

        ErrorResponse body = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                message,
                errorCode
        );
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
