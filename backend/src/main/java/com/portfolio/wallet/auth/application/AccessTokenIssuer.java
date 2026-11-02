package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.common.security.JwtProperties;
import com.portfolio.wallet.common.security.RequiredSecrets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Signs access tokens: RS256 with {@code kid} (documentations/03 §3.4). */
@Component
public class AccessTokenIssuer {

  private final JwtEncoder encoder;
  private final JwtProperties jwtProperties;
  private final String kid;

  public AccessTokenIssuer(
      JwtEncoder encoder, JwtProperties jwtProperties, RequiredSecrets secrets) {
    this.encoder = encoder;
    this.jwtProperties = jwtProperties;
    this.kid = secrets.jwtKid();
  }

  public String issue(UUID userId, Role role, UUID sessionId, Instant now, Instant expiresAt) {
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(userId.toString())
            .claim(JwtProperties.ROLES_CLAIM, List.of(role.name()))
            .claim(JwtProperties.SESSION_CLAIM, sessionId.toString())
            .id(UUID.randomUUID().toString())
            .issuer(jwtProperties.issuer())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
