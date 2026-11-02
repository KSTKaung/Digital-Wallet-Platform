package com.portfolio.wallet.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** For web slices, which have no secrets or sessions: every bearer token is invalid. */
@TestConfiguration
public class RejectingJwtDecoder {

  @Bean
  JwtDecoder jwtDecoder() {
    return token -> {
      throw new BadJwtException("rejected in this slice");
    };
  }
}
