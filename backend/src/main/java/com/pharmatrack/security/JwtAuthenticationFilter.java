package com.pharmatrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.dto.ErrorResponse;
import com.pharmatrack.entity.UserAccount;
import com.pharmatrack.exception.SensitiveDataSanitizer;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.util.AppTime;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stateless Bearer JWT authentication filter.
 * Validates token signature, expiration, live database enabled state, role match, and tokenVersion
 * on every authenticated request. Instantiated inside {@link SecurityConfig} so it executes once
 * within Spring Security's filter chain rather than as a duplicate servlet filter.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserAccountRepository userAccountRepository;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   UserAccountRepository userAccountRepository,
                                   AuthenticationEntryPoint authenticationEntryPoint) {
        this(jwtService, userAccountRepository, authenticationEntryPoint, AppTime.createObjectMapper());
    }

    public JwtAuthenticationFilter(JwtService jwtService,
                                   UserAccountRepository userAccountRepository,
                                   AuthenticationEntryPoint authenticationEntryPoint,
                                   ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userAccountRepository = userAccountRepository;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.objectMapper = objectMapper != null ? objectMapper : AppTime.createObjectMapper();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = extractApplicationPath(request);
        if (HttpMethod.GET.matches(request.getMethod()) && "/api/health".equals(path)) {
            return true;
        }
        return HttpMethod.POST.matches(request.getMethod()) && "/api/auth/login".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || authHeader.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (authHeader.length() < BEARER_PREFIX.length()
                || !authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            failAuthentication(request, response, new BadCredentialsException("Authentication token is missing"));
            return;
        }

        try {
            JwtService.ParsedJwtToken parsed = jwtService.parseAndValidate(token);

            UserAccount user = userAccountRepository.findById(parsed.userId())
                    .orElseThrow(() -> new BadCredentialsException("User account is unavailable"));

            if (!user.getUsername().equalsIgnoreCase(parsed.username())) {
                throw new BadCredentialsException("Authentication token does not match account");
            }
            if (!user.isEnabled()) {
                throw new DisabledException("User account is disabled");
            }
            if (user.getTokenVersion() != parsed.tokenVersion() || user.getRole() != parsed.role()) {
                throw new BadCredentialsException("Authentication token has been revoked");
            }

            CustomUserDetails principal = CustomUserDetails.fromEntity(user);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (AuthenticationException ex) {
            failAuthentication(request, response, ex);
            return;
        } catch (DataAccessException | TransactionException ex) {
            failFilterInfrastructureError(
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Service temporarily unavailable",
                    ex
            );
            return;
        } catch (RuntimeException ex) {
            failFilterInfrastructureError(
                    request,
                    response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null,
                    ex
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void failAuthentication(HttpServletRequest request,
                                    HttpServletResponse response,
                                    AuthenticationException ex) throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        String safeMessage = SensitiveDataSanitizer.sanitizeAuthenticationMessage(ex);
        request.setAttribute(RestAuthenticationEntryPoint.AUTH_ERROR_MESSAGE_ATTR, safeMessage);
        authenticationEntryPoint.commence(request, response, ex);
    }

    private void failFilterInfrastructureError(HttpServletRequest request,
                                               HttpServletResponse response,
                                               HttpStatus status,
                                               String customSafeMessage,
                                               Throwable ex) throws IOException {
        SecurityContextHolder.clearContext();
        String errorRef = SensitiveDataSanitizer.generateErrorReference();
        log.error("Authentication filter error ref={} request={} detail={}",
                errorRef,
                SensitiveDataSanitizer.sanitizeRequestSummary(request),
                SensitiveDataSanitizer.formatSafeExceptionForLog(ex));

        if (response.isCommitted()) {
            return;
        }

        String clientMessage = customSafeMessage != null
                ? customSafeMessage
                : SensitiveDataSanitizer.buildGenericServerErrorMessage(errorRef);

        ErrorResponse body = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                clientMessage
        );
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }

    private static String extractApplicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }
}
