package com.portfolio.wallet.common.security;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Validates access tokens with the public key derived from the configured private key: RS256 only,
 * then key id, expiry, issuer and the session. Test slices do not scan this class.
 */
@Configuration
public class JwtDecoderConfiguration {

  @Bean
  JwtDecoder jwtDecoder(
      RequiredSecrets secrets, JwtProperties properties, SessionValidator sessions, Clock clock) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(secrets.jwtPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    // No clock skew: this application is both the signer and the only validator.
    JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
    timestamps.setClock(clock);
    OAuth2TokenValidator<Jwt> claims =
        new DelegatingOAuth2TokenValidator<>(
            keyId(secrets.jwtKid()), timestamps, new JwtIssuerValidator(properties.issuer()));
    OAuth2TokenValidator<Jwt> session = session(sessions);
    // Not one delegating validator, which runs all of its members: an expired token must not reach
    // the session check, or using it would count as activity and keep an idle session alive.
    decoder.setJwtValidator(
        jwt -> {
          OAuth2TokenValidatorResult result = claims.validate(jwt);
          return result.hasErrors() ? result : session.validate(jwt);
        });
    return decoder;
  }

  // One active signing key (documentations/06 MVP-ADR-23), so the header must name exactly that.
  private static OAuth2TokenValidator<Jwt> keyId(String kid) {
    OAuth2TokenValidatorResult refused =
        OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Unknown key id", null));
    return jwt ->
        kid.equals(jwt.getHeaders().get(JoseHeaderNames.KID))
            ? OAuth2TokenValidatorResult.success()
            : refused;
  }

  private static OAuth2TokenValidator<Jwt> session(SessionValidator sessions) {
    OAuth2TokenValidatorResult refused =
        OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Session is not active", null));
    return jwt -> {
      String sessionId = jwt.getClaimAsString(JwtProperties.SESSION_CLAIM);
      String subject = jwt.getSubject();
      if (sessionId == null || subject == null) {
        return refused;
      }
      try {
        return sessions.isActive(UUID.fromString(sessionId), UUID.fromString(subject))
            ? OAuth2TokenValidatorResult.success()
            : refused;
      } catch (IllegalArgumentException e) {
        return refused; // Correctly signed, but the claims are not ids.
      }
    };
  }
}
