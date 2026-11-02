package com.portfolio.wallet.common.security;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.time.ClockConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Baseline security (documentations/06 MVP-ADR-05): stateless JWT resource server, deny by default,
 * JSON 401/403 with the same body whatever the cause and no {@code WWW-Authenticate} detail.
 *
 * <p>Spring's session-based CSRF protection is off because requests authenticate with a bearer
 * header, which a browser does not attach by itself. The refresh-token cookie is the exception: the
 * two endpoints that a cookie can reach are protected by {@link CookieEndpointCsrfFilter} and
 * {@code SameSite=Strict} (MVP-ADR-14). CORS stays non-credentialed, because the SPA is
 * same-origin.
 *
 * <p>Filter order: the per-IP limit comes first on {@code /auth/**}, so a flood is refused before
 * the CSRF check and before any Argon2id work; the per-user limit comes after authentication, which
 * is what supplies its key. The client IP is the container's remote address: Tomcat replaces it
 * from {@code X-Forwarded-For} only for a peer in {@code server.tomcat.remoteip.internal-proxies}.
 */
@Configuration
@Import(ClockConfiguration.class)
@EnableMethodSecurity
@EnableConfigurationProperties({
  CorsProperties.class,
  RateLimitProperties.class,
  JwtProperties.class
})
public class SecurityConfig {

  private static final String[] PUBLIC_PATHS = {
    "/actuator/health", "/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/error"
  };

  private static final String[] PUBLIC_AUTH_PATHS = {
    "/api/v1/auth/register",
    "/api/v1/auth/login",
    "/api/v1/auth/refresh",
    "/api/v1/auth/verify-otp",
    "/api/v1/auth/resend-otp",
    "/api/v1/auth/forgot-password",
    "/api/v1/auth/reset-password"
  };

  private static final String[] COOKIE_PATHS = {"/api/v1/auth/refresh", "/api/v1/auth/logout"};

  private static final String AUTH_PATHS = "/api/v1/auth/**";
  private static final String[] HEALTH_PATHS = {"/actuator/health", "/actuator/health/**"};
  private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

  private static final String ROLE_PREFIX = "ROLE_";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JsonSecurityErrorHandler errors,
      JwtDecoder jwtDecoder,
      CorsProperties corsProperties,
      @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource,
      RateLimitProperties limits,
      Clock clock)
      throws Exception {
    RequestMatcher publicAuth = anyPost(PUBLIC_AUTH_PATHS);
    RequestMatcher authPaths = PathPatternRequestMatcher.withDefaults().matcher(AUTH_PATHS);
    CorsFilter cors = new CorsFilter(corsSource);
    cors.setCorsProcessor(new JsonCorsProcessor(errors));
    http.csrf(csrf -> csrf.disable())
        // Added as a filter, because the CORS configurer has no way to set the processor.
        .cors(c -> c.disable())
        .addFilter(cors)
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .headers(
            h ->
                h.frameOptions(f -> f.deny())
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
        .oauth2ResourceServer(
            server ->
                server
                    .authenticationEntryPoint(errors)
                    .accessDeniedHandler(errors)
                    .bearerTokenResolver(bearerTokensOutside(publicAuth))
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(rolesToAuthorities())))
        // Before CORS, which would answer a foreign Origin with its own plain-text 403 and not the
        // documented body. It matches POST only, so preflights still reach CORS.
        .addFilterBefore(
            new CookieEndpointCsrfFilter(anyPost(COOKIE_PATHS), corsProperties, errors),
            CorsFilter.class)
        .addFilterBefore(
            new RateLimitFilter(
                "auth",
                authPaths,
                request -> ClientAddresses.rateLimitKey(request.getRemoteAddr()),
                new RateLimiter(
                    limits.authRequestsPerMinutePerIp(),
                    RATE_LIMIT_WINDOW,
                    limits.maxTrackedKeys(),
                    clock),
                errors),
            CookieEndpointCsrfFilter.class)
        .addFilterAfter(
            new RateLimitFilter(
                "user",
                new NegatedRequestMatcher(new OrRequestMatcher(authPaths, anyMethod(HEALTH_PATHS))),
                request -> authenticatedUser(),
                new RateLimiter(
                    limits.otherRequestsPerMinutePerUser(),
                    RATE_LIMIT_WINDOW,
                    limits.maxTrackedKeys(),
                    clock),
                errors),
            BearerTokenAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(PUBLIC_PATHS)
                    .permitAll()
                    .requestMatchers(publicAuth)
                    .permitAll()
                    .anyRequest()
                    .authenticated());
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

  private static RequestMatcher anyPost(String[] paths) {
    return new OrRequestMatcher(
        Arrays.stream(paths)
            .<RequestMatcher>map(
                path -> PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path))
            .toList());
  }

  private static RequestMatcher anyMethod(String[] paths) {
    return new OrRequestMatcher(
        Arrays.stream(paths)
            .<RequestMatcher>map(path -> PathPatternRequestMatcher.withDefaults().matcher(path))
            .toList());
  }

  // Null for an anonymous request, which is refused or served without any user to count against.
  private static String authenticatedUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null
            || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken
        ? null
        : authentication.getName();
  }

  // A stale bearer token must not fail a public endpoint: an expired access token is exactly why a
  // client calls refresh.
  private static BearerTokenResolver bearerTokensOutside(RequestMatcher publicPaths) {
    DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
    return request -> publicPaths.matches(request) ? null : resolver.resolve(request);
  }

  // The claim holds bare role names; the prefix is what hasRole('ADMIN') looks for.
  private static JwtAuthenticationConverter rolesToAuthorities() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(JwtProperties.ROLES_CLAIM);
    authorities.setAuthorityPrefix(ROLE_PREFIX);
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }
}
