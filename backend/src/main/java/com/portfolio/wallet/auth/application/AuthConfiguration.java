package com.portfolio.wallet.auth.application;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.portfolio.wallet.auth.domain.LockoutPolicy;
import com.portfolio.wallet.auth.domain.OtpPolicy;
import com.portfolio.wallet.auth.domain.PasswordPolicy;
import com.portfolio.wallet.auth.domain.SessionPolicy;
import com.portfolio.wallet.common.security.RequiredSecrets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Registers the authentication parameters and builds the domain policies from them. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

  @Bean
  PasswordPolicy passwordPolicy(AuthProperties properties) {
    AuthProperties.Password password = properties.password();
    return new PasswordPolicy(password.minLength(), password.maxLength(), password.historyDepth());
  }

  @Bean
  OtpPolicy otpPolicy(AuthProperties properties) {
    AuthProperties.Otp otp = properties.otp();
    return new OtpPolicy(
        otp.digits(),
        otp.ttl(),
        otp.maxAttempts(),
        otp.resendCooldown(),
        otp.maxResendsPerHour(),
        otp.usedUpCap(),
        otp.usedUpWindow());
  }

  @Bean
  LockoutPolicy lockoutPolicy(AuthProperties properties) {
    return new LockoutPolicy(
        properties.lockout().maxFailedAttempts(), properties.lockout().duration());
  }

  @Bean
  SessionPolicy sessionPolicy(AuthProperties properties) {
    return new SessionPolicy(
        properties.session().idleTimeout(), properties.session().lastSeenWriteInterval());
  }

  @Bean
  JwtEncoder jwtEncoder(RequiredSecrets secrets) {
    RSAKey key =
        new RSAKey.Builder(secrets.jwtPublicKey())
            .privateKey(secrets.jwtPrivateKey())
            .keyID(secrets.jwtKid())
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
  }
}
