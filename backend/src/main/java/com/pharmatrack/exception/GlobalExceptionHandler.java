package com.pharmatrack.exception;

import com.pharmatrack.dto.ErrorResponse;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized exception handler providing consistent, sanitized JSON error responses
 * across all REST controllers while preserving useful validation/domain messages and IST timestamps.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String NOT_FOUND_FALLBACK = "Requested resource was not found";
    private static final String CONFLICT_DATA_INTEGRITY_MESSAGE =
            "Operation could not be completed because it conflicts with existing data or database integrity constraints";
    private static final String CONFLICT_LOCKING_MESSAGE =
            "The record was modified concurrently or is temporarily locked. Please refresh and retry.";
    private static final String FORBIDDEN_MESSAGE = "You do not have permission to access this resource";
    private static final String BAD_REQUEST_FALLBACK = "Invalid request";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(ResourceNotFoundException ex) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Not Found",
                safeDomainMessage(ex.getMessage(), NOT_FOUND_FALLBACK)
        );
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoResourceFound(Exception ex) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Not Found",
                NOT_FOUND_FALLBACK
        );
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResourceException(DuplicateResourceException ex) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                safeDomainMessage(ex.getMessage(), "Resource already exists")
        );
    }

    @ExceptionHandler({DataIntegrityViolationException.class, org.hibernate.exception.ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(Exception ex) {
        String errorRef = SensitiveDataSanitizer.generateErrorReference();
        log.warn("Data integrity conflict ref={} request={} detail={}",
                errorRef,
                currentRequestSummary(),
                SensitiveDataSanitizer.formatSafeExceptionForLog(ex));
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                CONFLICT_DATA_INTEGRITY_MESSAGE
        );
    }

    @ExceptionHandler({
            ConcurrencyFailureException.class,
            OptimisticLockException.class,
            PessimisticLockException.class,
            LockTimeoutException.class
    })
    public ResponseEntity<ErrorResponse> handleConcurrencyOrLockFailure(Exception ex) {
        String errorRef = SensitiveDataSanitizer.generateErrorReference();
        log.warn("Concurrency or lock conflict ref={} request={} detail={}",
                errorRef,
                currentRequestSummary(),
                SensitiveDataSanitizer.formatSafeExceptionForLog(ex));
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                CONFLICT_LOCKING_MESSAGE
        );
    }

    @ExceptionHandler(InvalidRecipeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRecipeException(InvalidRecipeException ex) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                safeDomainMessage(ex.getMessage(), BAD_REQUEST_FALLBACK)
        );
    }

    @ExceptionHandler(InvalidOrderException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrderException(InvalidOrderException ex) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                safeDomainMessage(ex.getMessage(), BAD_REQUEST_FALLBACK)
        );
    }

    @ExceptionHandler(InvalidExecutionStepException.class)
    public ResponseEntity<ErrorResponse> handleInvalidExecutionStepException(InvalidExecutionStepException ex) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                safeDomainMessage(ex.getMessage(), BAD_REQUEST_FALLBACK)
        );
    }

    @ExceptionHandler(InvalidQualityOperationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidQualityOperationException(InvalidQualityOperationException ex) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                safeDomainMessage(ex.getMessage(), BAD_REQUEST_FALLBACK)
        );
    }

    @ExceptionHandler(InvalidDeviationOperationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDeviationOperationException(InvalidDeviationOperationException ex) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                safeDomainMessage(ex.getMessage(), BAD_REQUEST_FALLBACK)
        );
    }

    @ExceptionHandler(InvalidUserOperationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUserOperationException(InvalidUserOperationException ex) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                safeDomainMessage(ex.getMessage(), BAD_REQUEST_FALLBACK)
        );
    }

    @ExceptionHandler(InvalidInventoryOperationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInventoryOperationException(InvalidInventoryOperationException ex) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                safeDomainMessage(ex.getMessage(), BAD_REQUEST_FALLBACK)
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        String safeMessage = SensitiveDataSanitizer.sanitizeAuthenticationMessage(ex);
        if (SensitiveDataSanitizer.GENERIC_AUTH_FAILURE_MESSAGE.equals(safeMessage)
                && ex != null
                && !safeMessage.equals(ex.getMessage())) {
            log.warn("Authentication failure sanitized request={} detail={}",
                    currentRequestSummary(),
                    SensitiveDataSanitizer.formatSafeExceptionForLog(ex));
        }
        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                safeMessage
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                FORBIDDEN_MESSAGE
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String safeMethod = SensitiveDataSanitizer.sanitizeSafeHttpMethod(ex.getMethod());
        String message = safeMethod != null
                ? "HTTP method '" + safeMethod + "' is not supported for this endpoint"
                : "HTTP method is not supported for this endpoint";
        return buildErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                "Method Not Allowed",
                message
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return buildErrorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported Media Type",
                "Unsupported Content-Type. Expected 'application/json'"
        );
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_ACCEPTABLE.value(),
                "Not Acceptable",
                "Requested response media type is not supported"
        );
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(
                    fieldError.getField(),
                    safeDomainMessage(fieldError.getDefaultMessage(), "Invalid field value")
            );
        }
        if (errors.isEmpty()) {
            for (ObjectError globalError : ex.getBindingResult().getGlobalErrors()) {
                errors.putIfAbsent(
                        globalError.getObjectName(),
                        safeDomainMessage(globalError.getDefaultMessage(), "Invalid request payload")
                );
            }
        }

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Input validation failed for one or more fields",
                errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String field = extractLeafPropertyName(violation.getPropertyPath());
            errors.putIfAbsent(
                    field,
                    safeDomainMessage(violation.getMessage(), "Invalid parameter value")
            );
        }
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Input validation failed for one or more parameters",
                errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidationException(HandlerMethodValidationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            String paramName = result.getMethodParameter() != null && result.getMethodParameter().getParameterName() != null
                    ? SensitiveDataSanitizer.sanitizeParameterName(result.getMethodParameter().getParameterName())
                    : "parameter";
            for (MessageSourceResolvable err : result.getResolvableErrors()) {
                errors.putIfAbsent(
                        paramName,
                        safeDomainMessage(err.getDefaultMessage(), "Invalid parameter value")
                );
            }
        }
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Input validation failed for one or more parameters",
                errors.isEmpty() ? null : errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                "Malformed JSON request or invalid enum value"
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String safeParam = SensitiveDataSanitizer.sanitizeParameterName(ex.getName());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                "Invalid value for parameter '" + safeParam + "'"
        );
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingPathVariableException.class,
            MissingRequestHeaderException.class,
            ServletRequestBindingException.class
    })
    public ResponseEntity<ErrorResponse> handleRequestBindingErrors(ServletRequestBindingException ex) {
        String message;
        if (ex instanceof MissingServletRequestParameterException missingParam) {
            message = "Required request parameter '"
                    + SensitiveDataSanitizer.sanitizeParameterName(missingParam.getParameterName())
                    + "' is missing";
        } else if (ex instanceof MissingPathVariableException missingPathVar) {
            message = "Required path variable '"
                    + SensitiveDataSanitizer.sanitizeParameterName(missingPathVar.getVariableName())
                    + "' is missing";
        } else if (ex instanceof MissingRequestHeaderException missingHeader) {
            message = "Required request header '"
                    + SensitiveDataSanitizer.sanitizeParameterName(missingHeader.getHeaderName())
                    + "' is missing";
        } else {
            message = "Missing or invalid required request parameter or header";
        }
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        return buildErrorResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Payload Too Large",
                "Request payload exceeds maximum allowed size"
        );
    }

    @ExceptionHandler({TransactionSystemException.class, PersistenceException.class})
    public ResponseEntity<ErrorResponse> handleTransactionOrPersistenceException(Exception ex) {
        ConstraintViolationException beanViolation = findCause(ex, ConstraintViolationException.class);
        if (beanViolation != null) {
            return handleConstraintViolationException(beanViolation);
        }

        org.hibernate.exception.ConstraintViolationException hibernateConstraint =
                findCause(ex, org.hibernate.exception.ConstraintViolationException.class);
        SQLIntegrityConstraintViolationException sqlConstraint =
                findCause(ex, SQLIntegrityConstraintViolationException.class);
        if (hibernateConstraint != null || sqlConstraint != null) {
            return handleDataIntegrityViolation(ex);
        }

        if (findCause(ex, OptimisticLockException.class) != null
                || findCause(ex, PessimisticLockException.class) != null
                || findCause(ex, LockTimeoutException.class) != null) {
            return handleConcurrencyOrLockFailure(ex);
        }

        return logAndBuildUnexpectedServerError(ex);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, SQLException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseException(Exception ex) {
        return logAndBuildUnexpectedServerError(ex);
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleSpringErrorResponseException(ErrorResponseException ex) {
        HttpStatusCode statusCode = ex.getStatusCode();
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        if (statusCode.is5xxServerError() || resolved == null) {
            return logAndBuildUnexpectedServerError(ex);
        }
        String detail = ex.getBody().getDetail();
        String message = safeDomainMessage(detail, resolved.getReasonPhrase());
        return buildErrorResponse(resolved, resolved.getReasonPhrase(), message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        return logAndBuildUnexpectedServerError(ex);
    }

    private ResponseEntity<ErrorResponse> logAndBuildUnexpectedServerError(Exception ex) {
        String errorRef = SensitiveDataSanitizer.generateErrorReference();
        log.error("Unhandled server exception ref={} request={} detail={}",
                errorRef,
                currentRequestSummary(),
                SensitiveDataSanitizer.formatSafeExceptionForLog(ex));

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                SensitiveDataSanitizer.buildGenericServerErrorMessage(errorRef)
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    private static ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, String error, String message) {
        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),
                error,
                message
        );
        return ResponseEntity.status(status).body(errorResponse);
    }

    private static String safeDomainMessage(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        if (SensitiveDataSanitizer.containsSensitiveTechnicalMarkers(candidate)) {
            return fallback;
        }
        return candidate;
    }

    private static String extractLeafPropertyName(Path propertyPath) {
        if (propertyPath == null) {
            return "parameter";
        }
        String leaf = null;
        for (Path.Node node : propertyPath) {
            if (node.getName() != null && !node.getName().isBlank()) {
                leaf = node.getName();
            }
        }
        return SensitiveDataSanitizer.sanitizeParameterName(leaf != null ? leaf : "parameter");
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> targetType) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 15) {
            if (targetType.isInstance(current)) {
                return targetType.cast(current);
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static String currentRequestSummary() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return SensitiveDataSanitizer.sanitizeRequestSummary(servletAttributes.getRequest());
        }
        return "[NO_HTTP_REQUEST]";
    }
}
