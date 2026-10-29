package com.portfolio.wallet.common.security;

import com.portfolio.wallet.common.web.CorrelationId;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Baseline security (documentations/06 MVP-ADR-05): stateless, deny by default, JSON 401/403. Only
 * health/info and, when enabled by profile, the OpenAPI docs are public. Authentication (JWT) is
 * added in PR 3.
 *
 * <p>CSRF protection is disabled because no endpoint is cookie-authenticated yet. It must be
 * revisited when the refresh-token cookie is introduced (MVP-ADR-14).
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

  private static final String[] PUBLIC_PATHS = {
    "/actuator/health", "/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/error"
  };

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JsonSecurityErrorHandler errors)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .headers(
            h ->
                h.frameOptions(f -> f.deny())
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
        .authorizeHttpRequests(
            auth -> auth.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated());
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(properties.allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            "Idempotency-Key",
            CorrelationId.HEADER,
            "X-Requested-With"));
    config.setExposedHeaders(List.of(CorrelationId.HEADER, "Retry-After"));
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
