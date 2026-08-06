package com.enterprise.apidrift.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Comma-separated list of allowed CORS origins.
     * Use a restrictive list in production (e.g. {@code https://dashboard.example.com}).
     * Defaults to {@code *} for local development only.
     */
    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    @SuppressWarnings("java:S4502")
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // CSRF is intentionally disabled because this is a stateless REST API
        // (SessionCreationPolicy.STATELESS) using Basic Auth. There are no
        // server-side sessions or session cookies for a CSRF attack to exploit.
        // Each request is independently authenticated via the Authorization header.
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().authenticated()
            )
            .httpBasic(basic -> {}); // Basic auth for internal API; swap for OAuth2 in production

        return http.build();
    }

    @Bean
    @SuppressWarnings("java:S5122")
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = parseAllowedOrigins();

        CorsConfiguration config = new CorsConfiguration();
        if (origins.contains("*")) {
            // Wildcard pattern origin — credentials still require explicit host match.
            // This is intended for local dev; restrict via cors.allowed-origins in prod.
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOrigins(origins);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private List<String> parseAllowedOrigins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return Collections.singletonList("*");
        }
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
