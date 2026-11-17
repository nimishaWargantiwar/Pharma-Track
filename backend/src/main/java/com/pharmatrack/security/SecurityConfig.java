package com.pharmatrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharmatrack.repository.UserAccountRepository;
import com.pharmatrack.util.AppTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless Spring Security configuration enforcing JWT authentication and Role-Based Access Control (RBAC)
 * across all PharmaTrack REST APIs.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, SecurityConfigProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new RestAuthenticationEntryPoint(objectMapperProvider.getIfAvailable(AppTime::createObjectMapper));
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new RestAccessDeniedHandler(objectMapperProvider.getIfAvailable(AppTime::createObjectMapper));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(SecurityConfigProperties securityProperties,
                                                           Environment environment) {
        boolean isProd = environment != null && environment.acceptsProfiles(Profiles.of("prod"));
        List<String> rawOrigins = securityProperties.getCors() != null
                ? securityProperties.getCors().getAllowedOrigins()
                : null;
        List<String> configuredOrigins = CorsOriginSupport.normalizeAndValidateOrigins(rawOrigins, isProd);
        if (!isProd && configuredOrigins.isEmpty()) {
            configuredOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173");
        }

        if (isProd && configuredOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "Production configuration error: CORS_ALLOWED_ORIGINS must specify explicit frontend origin(s) and cannot be empty or '*'.");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(configuredOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin", "Cache-Control", "Pragma"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            ObjectProvider<JwtService> jwtServiceProvider,
            ObjectProvider<UserAccountRepository> userAccountRepositoryProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/ai/query").authenticated()
                        .requestMatchers("/api/ai/conversations", "/api/ai/conversations/**").authenticated()
                        .requestMatchers("/api/admin/users/**").hasRole("ADMIN")

                        // Products
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/products/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/products/*/status").hasRole("ADMIN")

                        // Master Recipes (OPERATOR intentionally excluded from read and write access)
                        .requestMatchers(HttpMethod.GET, "/api/recipes", "/api/recipes/*", "/api/recipes/product/*")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR", "QC_ANALYST", "QA_REVIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/recipes", "/api/recipes/*/new-version")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.PUT, "/api/recipes/*")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/recipes/*")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/recipes/*/status")
                        .hasAnyRole("ADMIN", "QA_REVIEWER")

                        // Manufacturing Orders
                        .requestMatchers(HttpMethod.GET,
                                "/api/orders",
                                "/api/orders/*",
                                "/api/orders/number/*",
                                "/api/orders/product/*",
                                "/api/orders/*/batches").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/orders")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*/status")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")

                        // Batches, Production Execution & QR Traceability
                        .requestMatchers(HttpMethod.GET,
                                "/api/batches/execution",
                                "/api/batches/*",
                                "/api/batches/number/*",
                                "/api/batches/*/steps",
                                "/api/batches/*/qr",
                                "/api/batches/qr/*",
                                "/api/batches/*/traceability",
                                "/api/batches/traceability/*").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/batches/*/status")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/batches/*/steps/initialize")
                        .hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/batches/*/steps/*/start",
                                "/api/batches/*/steps/*/complete").hasRole("OPERATOR")

                        // AI Batch Analysis Reports
                        .requestMatchers(HttpMethod.POST, "/api/batches/*/analysis-reports")
                        .hasRole("PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/batches/*/analysis-reports/*/archive")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/batches/*/analysis-reports/*")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET,
                                "/api/batches/*/analysis-reports",
                                "/api/batches/*/analysis-reports/*")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR", "QA_REVIEWER")

                        // Quality Management (QC, QA Disposition, Append-Only Audit Trail)
                        .requestMatchers(HttpMethod.GET, "/api/quality/batches", "/api/quality/batches/*")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/quality/batches/*/checks", "/api/quality/checks/*/correct")
                        .hasRole("QC_ANALYST")
                        .requestMatchers("/api/quality/checks/*").hasRole("QC_ANALYST")
                        .requestMatchers(HttpMethod.POST, "/api/quality/batches/*/disposition").hasRole("QA_REVIEWER")
                        .requestMatchers("/api/quality/audit-logs").hasAnyRole("ADMIN", "QA_REVIEWER")

                        // Deviations & CAPA (Specific GET /api/quality/deviations/* rule must precede generic /* rule)
                        .requestMatchers(HttpMethod.GET,
                                "/api/quality/deviations",
                                "/api/quality/deviations/*",
                                "/api/quality/batches/*/deviations",
                                "/api/quality/batches/*/linkable-records").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/quality/deviations")
                        .hasAnyRole("PRODUCTION_SUPERVISOR", "OPERATOR", "QC_ANALYST", "QA_REVIEWER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/quality/deviations/*/start-investigation",
                                "/api/quality/deviations/*/record-root-cause",
                                "/api/quality/deviations/*/submit-verification")
                        .hasAnyRole("PRODUCTION_SUPERVISOR", "QC_ANALYST", "QA_REVIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/quality/deviations/*/capas")
                        .hasAnyRole("PRODUCTION_SUPERVISOR", "QA_REVIEWER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/quality/capas/*/start",
                                "/api/quality/capas/*/complete")
                        .hasAnyRole("PRODUCTION_SUPERVISOR", "QC_ANALYST", "QA_REVIEWER")
                        .requestMatchers(HttpMethod.POST,
                                "/api/quality/deviations/*/start-capa",
                                "/api/quality/deviations/*/reopen-capa",
                                "/api/quality/deviations/*/close",
                                "/api/quality/capas/*/cancel").hasRole("QA_REVIEWER")
                        .requestMatchers("/api/quality/deviations/*", "/api/quality/capas/*")
                        .hasRole("QA_REVIEWER")

                        // Raw Material Inventory & Warehouse Management
                        .requestMatchers(HttpMethod.GET,
                                "/api/inventory/summary",
                                "/api/inventory/recipe-material-references",
                                "/api/inventory/materials",
                                "/api/inventory/materials/*",
                                "/api/inventory/warehouses",
                                "/api/inventory/warehouses/*/locations",
                                "/api/inventory/locations",
                                "/api/inventory/lots",
                                "/api/inventory/lots/*",
                                "/api/inventory/movements",
                                "/api/inventory/orders/*/requirements").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/inventory/materials",
                                "/api/inventory/warehouses",
                                "/api/inventory/warehouses/*/locations",
                                "/api/inventory/receipts").hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.PUT,
                                "/api/inventory/materials/*",
                                "/api/inventory/warehouses/*",
                                "/api/inventory/locations/*").hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/inventory/materials/*/status",
                                "/api/inventory/warehouses/*/status",
                                "/api/inventory/locations/*/status").hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR")
                        .requestMatchers(HttpMethod.POST, "/api/inventory/issues")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/inventory/adjustments")
                        .hasAnyRole("ADMIN", "PRODUCTION_SUPERVISOR", "QA_REVIEWER")

                        .anyRequest().denyAll()
                );

        JwtService jwtService = jwtServiceProvider.getIfAvailable();
        UserAccountRepository userAccountRepository = userAccountRepositoryProvider.getIfAvailable();
        if (jwtService != null && userAccountRepository != null) {
            JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(
                    jwtService,
                    userAccountRepository,
                    authenticationEntryPoint,
                    objectMapperProvider.getIfAvailable(AppTime::createObjectMapper)
            );
            http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
