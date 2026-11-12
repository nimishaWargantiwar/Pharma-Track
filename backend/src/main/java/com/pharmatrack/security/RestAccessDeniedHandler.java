package com.pharmatrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.ErrorResponse;
import com.pharmatrack.util.AppTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Returns consistent JSON {@link ErrorResponse} payloads with HTTP 403 when an authenticated user
 * lacks the required authority to access a protected route.
 */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final String DEFAULT_MESSAGE = "You do not have permission to access this resource";

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : AppTime.createObjectMapper();
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ErrorResponse body = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                DEFAULT_MESSAGE
        );
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
