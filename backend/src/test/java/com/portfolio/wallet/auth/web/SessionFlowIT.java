package com.portfolio.wallet.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.portfolio.securityfixture.RoleProbeController;
import com.portfolio.wallet.auth.application.AccessTokenIssuer;
import com.portfolio.wallet.auth.application.AuthProperties;
import com.portfolio.wallet.auth.application.KeyedHasher;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.security.RequiredSecrets;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** Tokens, refresh, logout, CSRF and session rejection (doc 08 §4A.5 cases 4, 5, 6). */
class SessionFlowIT extends AuthFlowSupport {

  @Autowired KeyedHasher hasher;
  @Autowired AuthProperties properties;
  @Autowired JwtEncoder encoder;
  @Autowired AccessTokenIssuer issuer;
  @Autowired RequiredSecrets secrets;

  private String missingTokenBody() throws Exception {
    return stable(mvc.perform(get(RoleProbeController.ANY)).andReturn().getResponse());
  }

  private void assertSame401(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(stable(response)).isEqualTo(missingTokenBody());
    assertThat(response.getHeader("WWW-Authenticate")).isNull();
  }

  private String revokedReasonOf(String accessToken) {
    return jdbc.queryForObject(
        "SELECT revoked_reason FROM auth.sessions WHERE id = ?::uuid",
        String.class,
        claims(accessToken).get("sid").asString());
  }

  private JsonNode claims(String jwt) {
    return part(jwt, 1);
  }

  private JsonNode part(String jwt, int index) {
    return jsonMapper.readTree(
        new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]), StandardCharsets.UTF_8));
  }

  private static String base64Url(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  // Login response ------------------------------------------------------------------------------

  @Test
  void theAccessTokenHasTheDocumentedHeaderAndClaims() throws Exception {
    String email = activeUser();
    MockHttpServletResponse response = login(email, PASSWORD);
    JsonNode body = jsonMapper.readTree(response.getContentAsString());
    String token = body.get("accessToken").asString();

    assertThat(body.properties()).hasSize(2);
    assertThat(body.get("expiresIn").asLong())
        .isEqualTo(properties.token().accessTtl().toSeconds());
    assertThat(part(token, 0).get("alg").asString()).isEqualTo("RS256");
    assertThat(part(token, 0).get("kid").asString()).isEqualTo(secrets.jwtKid());
    JsonNode claims = claims(token);
    assertThat(claims.get("sub").asString())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT id::text FROM auth.users WHERE email = ?", String.class, email));
    assertThat(claims.get("roles").get(0).asString()).isEqualTo("CUSTOMER");
    assertThat(claims.get("roles").size()).isEqualTo(1);
    assertThat(claims.get("exp").asLong() - claims.get("iat").asLong())
        .isEqualTo(properties.token().accessTtl().toSeconds());
    assertThat(claims.has("sid")).isTrue();
    assertThat(claims.has("jti")).isTrue();
    assertThat(claims.has("iss")).isTrue();
    assertThat(probe(token).getStatus()).isEqualTo(200);
  }

  @Test
  void theCookieHasTheRequiredAttributesAndIsAbsentFromTheBody() throws Exception {
    MockHttpServletResponse response = login(activeUser(), PASSWORD);
    String cookie = response.getHeader("Set-Cookie");
    String value = refreshCookieValue(response);

    assertThat(cookie)
        .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth")
        .contains("Max-Age=" + properties.token().refreshTtl().toSeconds());
    assertThat(Base64.getUrlDecoder().decode(value).length).isGreaterThanOrEqualTo(32);
    assertThat(response.getContentAsString()).doesNotContain(value).doesNotContain("refresh");
  }

  @Test
  void onlyTheHashOfARefreshTokenIsStored() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.refresh_tokens WHERE token_hash = ?",
                Integer.class,
                hasher.refreshToken(tokens.refreshToken())))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.refresh_tokens WHERE token_hash = ?",
                Integer.class,
                tokens.refreshToken()))
        .isZero();
  }

  // Refresh -------------------------------------------------------------------------------------

  @Test
  void refreshRotatesTheCookieAndIssuesAWorkingToken() throws Exception {
    Tokens first = loginTokens(activeUser());

    MockHttpServletResponse response = refresh(first.refreshToken());

    assertThat(response.getStatus()).isEqualTo(200);
    Tokens second = tokens(response);
    assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
    assertThat(response.getContentAsString()).doesNotContain(second.refreshToken());
    assertThat(probe(second.accessToken()).getStatus()).isEqualTo(200);
    assertThat(refresh(second.refreshToken()).getStatus()).isEqualTo(200);
  }

  @Test
  void aStaleBearerTokenDoesNotFailARefresh() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    MockHttpServletResponse response =
        mvc.perform(
                browserPost("refresh")
                    .header("Authorization", "Bearer expired.or.junk")
                    .cookie(new Cookie("refresh_token", tokens.refreshToken())))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void aMissingOrUnknownCookieIsTheGeneric401() throws Exception {
    assertSame401(mvc.perform(browserPost("refresh")).andReturn().getResponse());
    assertSame401(refresh("no-such-token"));
  }

  @Test
  void anExpiredRefreshTokenIsRefused() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    String hash = hasher.refreshToken(tokens.refreshToken());
    OffsetDateTime past = clock.instant().minusSeconds(1).atOffset(ZoneOffset.UTC);
    withTriggersDisabled(
        c ->
            update(
                c,
                "UPDATE auth.refresh_tokens SET expires_at = ? WHERE token_hash = ?",
                past,
                hash));
    assertSame401(refresh(tokens.refreshToken()));
  }

  @Test
  void reusingARotatedTokenRevokesTheWholeFamilyAndTheRevocationCommits() throws Exception {
    Tokens first = loginTokens(activeUser());
    Tokens second = tokens(refresh(first.refreshToken()));

    assertSame401(refresh(first.refreshToken()));

    assertThat(revokedReasonOf(first.accessToken())).isEqualTo("TOKEN_REUSE");
    assertSame401(refresh(second.refreshToken()));
    assertSame401(probe(second.accessToken()));
    String sid = claims(first.accessToken()).get("sid").asString();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.refresh_tokens"
                    + " WHERE session_id = ?::uuid AND revoked_at IS NULL",
                Integer.class,
                sid))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs"
                    + " WHERE action = 'SESSION_REVOKED' AND resource_id = ?",
                Integer.class,
                sid))
        .isEqualTo(1);
  }

  /** A replayed stolen token must not show its victim as having acted. */
  @Test
  void aReplayedTokenNamesItsOwnerButNeverAsTheActor() throws Exception {
    String email = activeUser();
    Tokens first = loginTokens(email);
    refresh(first.refreshToken());
    String owner =
        jdbc.queryForObject("SELECT id::text FROM auth.users WHERE email = ?", String.class, email);
    String correlationId = UUID.randomUUID().toString();

    assertSame401(
        mvc.perform(
                browserPost("refresh")
                    .header(CorrelationId.HEADER, correlationId)
                    .cookie(new Cookie("refresh_token", first.refreshToken())))
            .andReturn()
            .getResponse());

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT action, actor_id, actor_type, new_value ->> 'ownerId' AS owner"
                + " FROM audit.audit_logs WHERE correlation_id = ? ORDER BY id",
            correlationId);
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row).containsEntry("actor_id", null).containsEntry("owner", owner);
              assertThat(row.get("actor_type")).isIn("SYSTEM", "ANONYMOUS");
            });
  }

  @Test
  void ofConcurrentRefreshesExactlyOneWins() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    List<Integer> statuses = new ArrayList<>();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return refresh(tokens.refreshToken()).getStatus();
                }));
      }
      start.countDown();
      for (Future<Integer> future : futures) {
        statuses.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
    }
    assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
    assertThat(statuses.stream().filter(status -> status == 401)).hasSize(5);
  }

  // CSRF ----------------------------------------------------------------------------------------

  private void assertCsrfRefused(MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(403);
    JsonNode body = jsonMapper.readTree(response.getContentAsString());
    assertThat(body.get("errorCode").asString()).isEqualTo("FORBIDDEN");
    assertThat(body.get("success").asBoolean()).isFalse();
    assertThat(body.get("correlationId").asString()).isNotBlank();
  }

  @Test
  void refreshAndLogoutWithoutTheHeaderOrFromAForeignOrAbsentOriginAre403() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    Cookie cookie = new Cookie("refresh_token", tokens.refreshToken());
    String origin = cors.allowedOrigins().getFirst();
    String bearer = "Bearer " + tokens.accessToken();

    for (String endpoint : List.of("refresh", "logout")) {
      String path = API + endpoint;
      assertCsrfRefused(
          post(path).header("Origin", origin).header("Authorization", bearer).cookie(cookie));
      assertCsrfRefused(
          post(path)
              .header("Origin", origin)
              .header("X-Requested-With", " ")
              .header("Authorization", bearer)
              .cookie(cookie));
      assertCsrfRefused(
          post(path)
              .header("X-Requested-With", "XMLHttpRequest")
              .header("Authorization", bearer)
              .cookie(cookie));
      assertCsrfRefused(
          post(path)
              .header("Origin", "https://evil.example")
              .header("X-Requested-With", "XMLHttpRequest")
              .header("Authorization", bearer)
              .cookie(cookie));
    }

    // None of the refused requests rotated or revoked anything.
    assertThat(probe(tokens.accessToken()).getStatus()).isEqualTo(200);
    assertThat(refresh(tokens.refreshToken()).getStatus()).isEqualTo(200);
  }

  @Test
  void corsStaysNonCredentialed() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    MockHttpServletResponse response = refresh(tokens.refreshToken());
    assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
  }

  // Logout --------------------------------------------------------------------------------------

  @Test
  void logoutRevokesTheSessionClearsTheCookieAndIsAudited() throws Exception {
    Tokens tokens = loginTokens(activeUser());

    MockHttpServletResponse response =
        mvc.perform(browserPost("logout").header("Authorization", "Bearer " + tokens.accessToken()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(response.getHeader("Set-Cookie"))
        .contains("refresh_token=;", "Max-Age=0", "HttpOnly", "Secure", "SameSite=Strict")
        .contains("Path=/api/v1/auth");
    assertSame401(probe(tokens.accessToken()));
    assertSame401(refresh(tokens.refreshToken()));
    assertThat(revokedReasonOf(tokens.accessToken())).isEqualTo("LOGOUT");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'LOGOUT' AND resource_id = ?",
                Integer.class,
                claims(tokens.accessToken()).get("sid").asString()))
        .isEqualTo(1);
  }

  @Test
  void logoutNeedsABearerToken() throws Exception {
    assertSame401(mvc.perform(browserPost("logout")).andReturn().getResponse());
  }

  // Session rejection ---------------------------------------------------------------------------

  private void backdateLastSeen(String accessToken, Duration idle) {
    jdbc.update(
        "UPDATE auth.sessions SET last_seen_at = ? WHERE id = ?::uuid",
        clock.instant().minus(idle).atOffset(ZoneOffset.UTC),
        claims(accessToken).get("sid").asString());
  }

  private Instant lastSeen(String accessToken) {
    return jdbc.queryForObject(
            "SELECT last_seen_at FROM auth.sessions WHERE id = ?::uuid",
            Timestamp.class,
            claims(accessToken).get("sid").asString())
        .toInstant();
  }

  @Test
  void aSessionIdleForThirtyMinutesIsRevokedAndRefusedWithTheSameBody() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    Duration idle = properties.session().idleTimeout();

    backdateLastSeen(tokens.accessToken(), idle.minusSeconds(1));
    assertThat(probe(tokens.accessToken()).getStatus()).isEqualTo(200);

    backdateLastSeen(tokens.accessToken(), idle);
    assertSame401(probe(tokens.accessToken()));
    assertThat(revokedReasonOf(tokens.accessToken())).isEqualTo("IDLE_TIMEOUT");
  }

  @Test
  void refreshAfterThirtyIdleMinutesIsRefused() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    clock.advance(properties.session().idleTimeout());

    assertSame401(refresh(tokens.refreshToken()));

    assertThat(revokedReasonOf(tokens.accessToken())).isEqualTo("IDLE_TIMEOUT");
  }

  @Test
  void refreshJustBeforeTheIdleLimitKeepsTheSessionGoing() throws Exception {
    Tokens first = loginTokens(activeUser());
    clock.advance(properties.session().idleTimeout().minusSeconds(1));

    Tokens second = tokens(refresh(first.refreshToken()));
    clock.advance(properties.session().idleTimeout().minusSeconds(1));

    assertThat(refresh(second.refreshToken()).getStatus()).isEqualTo(200);
  }

  @Test
  void lastSeenIsWrittenAtMostOncePerInterval() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    Instant atLogin = lastSeen(tokens.accessToken());
    Duration interval = properties.session().lastSeenWriteInterval();

    clock.advance(interval.minusSeconds(1));
    assertThat(probe(tokens.accessToken()).getStatus()).isEqualTo(200);
    assertThat(lastSeen(tokens.accessToken())).isEqualTo(atLogin);

    clock.advance(Duration.ofSeconds(1));
    assertThat(probe(tokens.accessToken()).getStatus()).isEqualTo(200);
    assertThat(lastSeen(tokens.accessToken())).isEqualTo(clock.instant());
  }

  @Test
  void anExpiredTokenDoesNotCountAsActivity() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    Instant atLogin = lastSeen(tokens.accessToken());
    clock.advance(properties.token().accessTtl());

    assertSame401(probe(tokens.accessToken()));

    assertThat(lastSeen(tokens.accessToken())).isEqualTo(atLogin);
  }

  @Test
  void aFrozenUserIsRefusedAtOnceOnEveryPath() throws Exception {
    String email = activeUser();
    Tokens tokens = loginTokens(email);

    jdbc.update("UPDATE auth.users SET status = 'FROZEN' WHERE email = ?", email);

    assertSame401(probe(tokens.accessToken()));
    assertSame401(refresh(tokens.refreshToken()));
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);
  }

  // Token validation ----------------------------------------------------------------------------

  private String sign(
      JwtEncoder with, String issuerValue, Instant expiresAt, Map<String, Object> extra) {
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .subject(UUID.randomUUID().toString())
            .issuer(issuerValue)
            .issuedAt(clock.instant())
            .expiresAt(expiresAt)
            .id(UUID.randomUUID().toString());
    extra.forEach(claims::claim);
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(secrets.jwtKid()).build();
    return with.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }

  @Test
  void anExpiredTokenIs401() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    clock.advance(properties.token().accessTtl());
    assertSame401(probe(tokens.accessToken()));
  }

  @Test
  void aTamperedTokenIs401() throws Exception {
    String token = loginTokens(activeUser()).accessToken();
    String[] parts = token.split("\\.");
    String changedPayload =
        base64Url(
            new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("CUSTOMER", "ADMIN"));
    assertSame401(probe(parts[0] + "." + changedPayload + "." + parts[2]));
    assertSame401(probe("not-a-jwt"));
  }

  @Test
  void aTokenSignedWithAnotherKeyIs401() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    JwtEncoder other =
        new NimbusJwtEncoder(
            new ImmutableJWKSet<>(
                new JWKSet(
                    new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate())
                        .keyID(secrets.jwtKid())
                        .build())));
    JsonNode real = claims(tokens.accessToken());
    assertSame401(
        probe(
            sign(
                other,
                real.get("iss").asString(),
                clock.instant().plusSeconds(60),
                Map.of("sid", real.get("sid").asString()))));
  }

  @Test
  void algNoneIs401() throws Exception {
    JsonNode real = claims(loginTokens(activeUser()).accessToken());
    String header = base64Url("{\"alg\":\"none\"}");
    assertSame401(probe(header + "." + base64Url(real.toString()) + "."));
  }

  /** Signed by hand: the Spring encoder fills in a missing kid from the key. */
  private String resignedWithKid(String accessToken, String kid) throws Exception {
    JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256);
    if (kid != null) {
      header.keyID(kid);
    }
    SignedJWT jwt =
        new SignedJWT(header.build(), JWTClaimsSet.parse(claims(accessToken).toString()));
    jwt.sign(new RSASSASigner(secrets.jwtPrivateKey()));
    return jwt.serialize();
  }

  @Test
  void aValidTokenWithAWrongOrMissingKidIs401() throws Exception {
    String token = loginTokens(activeUser()).accessToken();

    assertThat(probe(resignedWithKid(token, secrets.jwtKid())).getStatus()).isEqualTo(200);
    assertSame401(probe(resignedWithKid(token, "another-key")));
    assertSame401(probe(resignedWithKid(token, null)));
  }

  @Test
  void aWrongIssuerOrUnusableSessionClaimIs401EvenWithTheRealKey() throws Exception {
    Instant soon = clock.instant().plusSeconds(60);
    String iss = claims(loginTokens(activeUser()).accessToken()).get("iss").asString();

    assertSame401(
        probe(sign(encoder, "someone-else", soon, Map.of("sid", UUID.randomUUID().toString()))));
    assertSame401(probe(sign(encoder, iss, soon, Map.of("sid", "not-a-uuid"))));
    assertSame401(probe(sign(encoder, iss, soon, Map.of())));
    assertSame401(probe(sign(encoder, iss, soon, Map.of("sid", UUID.randomUUID().toString()))));
  }

  @Test
  void aTokenWithAnotherUsersSessionIs401() throws Exception {
    String sid = claims(loginTokens(activeUser()).accessToken()).get("sid").asString();
    String token =
        issuer.issue(
            UUID.randomUUID(),
            Role.CUSTOMER,
            UUID.fromString(sid),
            clock.instant(),
            clock.instant().plus(properties.token().accessTtl()));
    assertSame401(probe(token));
  }

  // Roles ---------------------------------------------------------------------------------------

  private String adminToken() throws Exception {
    Map<String, Object> user =
        row("id", UUID.randomUUID(), "email", email(), "status", "ACTIVE", "role", "ADMIN");
    insert("auth.users", user);
    UUID sessionId = UUID.randomUUID();
    insert(
        "auth.sessions",
        row(
            "id",
            sessionId,
            "user_id",
            user.get("id"),
            "last_seen_at",
            clock.instant().atOffset(ZoneOffset.UTC)));
    return issuer.issue(
        (UUID) user.get("id"),
        Role.ADMIN,
        sessionId,
        clock.instant(),
        clock.instant().plus(properties.token().accessTtl()));
  }

  @Test
  void rolesAreEnforcedByMethodSecurity() throws Exception {
    String customer = loginTokens(activeUser()).accessToken();
    String admin = adminToken();

    assertThat(getWithToken(RoleProbeController.CUSTOMER, customer).getStatus()).isEqualTo(200);
    assertThat(getWithToken(RoleProbeController.ADMIN, admin).getStatus()).isEqualTo(200);

    MockHttpServletResponse denied = getWithToken(RoleProbeController.ADMIN, customer);
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(denied.getContentAsString()).contains("\"errorCode\":\"FORBIDDEN\"");
    assertThat(getWithToken(RoleProbeController.CUSTOMER, admin).getStatus()).isEqualTo(403);
  }

  // Audit of refusals ---------------------------------------------------------------------------

  private List<Map<String, Object>> auditRows(String correlationId) {
    return jdbc.queryForList(
        "SELECT action, actor_id, actor_type, result, new_value ->> 'path' AS path"
            + " FROM audit.audit_logs WHERE correlation_id = ?",
        correlationId);
  }

  @Test
  void aRoleDenialIsAuditedExactlyOnceWithTheActor() throws Exception {
    String email = activeUser();
    String token = loginTokens(email).accessToken();
    String correlationId = UUID.randomUUID().toString();

    MockHttpServletResponse denied =
        mvc.perform(
                get(RoleProbeController.ADMIN)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(CorrelationId.HEADER, correlationId))
            .andReturn()
            .getResponse();

    assertThat(denied.getStatus()).isEqualTo(403);
    List<Map<String, Object>> rows = auditRows(correlationId);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("action", "ACCESS_DENIED")
        .containsEntry("actor_type", "CUSTOMER")
        .containsEntry("result", "DENIED")
        .containsEntry("path", RoleProbeController.ADMIN)
        .containsEntry(
            "actor_id",
            jdbc.queryForObject("SELECT id FROM auth.users WHERE email = ?", UUID.class, email));
  }

  @Test
  void aStaffRoleDenialIsAuditedAsStaff() throws Exception {
    String correlationId = UUID.randomUUID().toString();

    mvc.perform(
        get(RoleProbeController.CUSTOMER)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
            .header(CorrelationId.HEADER, correlationId));

    assertThat(auditRows(correlationId)).hasSize(1);
    assertThat(auditRows(correlationId).getFirst()).containsEntry("actor_type", "STAFF");
  }

  @Test
  void aCsrfRefusalIsAuditedExactlyOnceAndWithoutAnActorBecauseItRunsFirst() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    String correlationId = UUID.randomUUID().toString();

    MockHttpServletResponse refused =
        mvc.perform(
                post(API + "logout")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                    .header(CorrelationId.HEADER, correlationId))
            .andReturn()
            .getResponse();

    assertThat(refused.getStatus()).isEqualTo(403);
    List<Map<String, Object>> rows = auditRows(correlationId);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("action", "CSRF_REFUSED")
        .containsEntry("actor_type", "ANONYMOUS")
        .containsEntry("actor_id", null);
  }

  @Test
  void refusalsAnyoneCanSendWithoutLimitAreNotAudited() throws Exception {
    String unauthenticated = UUID.randomUUID().toString();
    String foreignOrigin = UUID.randomUUID().toString();

    assertThat(
            mvc.perform(get(RoleProbeController.ANY).header(CorrelationId.HEADER, unauthenticated))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
    assertThat(
            mvc.perform(
                    get(RoleProbeController.ANY)
                        .header("Origin", "https://evil.example")
                        .header(CorrelationId.HEADER, foreignOrigin))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);

    assertThat(auditRows(unauthenticated)).isEmpty();
    assertThat(auditRows(foreignOrigin)).isEmpty();
  }

  @Test
  void changePasswordNeedsABearerAndForgotAndResetArePublic() throws Exception {
    assertThat(mvc.perform(post(API + "change-password")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    for (String path : List.of("forgot-password", "reset-password")) {
      assertThat(postJson(path, Map.of()).getStatus()).isEqualTo(400);
    }
  }

  // Absolute cap --------------------------------------------------------------------------------

  private void keepActive(String email) {
    jdbc.update(
        "UPDATE auth.sessions SET last_seen_at = ?"
            + " WHERE user_id = (SELECT id FROM auth.users WHERE email = ?)",
        clock.instant().atOffset(ZoneOffset.UTC),
        email);
  }

  @Test
  void anActiveSessionIsRefusedAtTheCapHoweverOftenItRotates() throws Exception {
    Duration lifetime = properties.token().refreshTtl();
    String email = activeUser();
    Tokens first = loginTokens(email);

    clock.advance(lifetime.minusDays(1));
    keepActive(email);
    MockHttpServletResponse rotated = refresh(first.refreshToken());
    assertThat(rotated.getStatus()).isEqualTo(200);
    // The cookie lives only as long as the session may.
    assertThat(rotated.getHeader(HttpHeaders.SET_COOKIE))
        .contains("Max-Age=" + Duration.ofDays(1).toSeconds());
    Tokens second = tokens(rotated);

    clock.advance(Duration.ofDays(1).minusSeconds(1));
    keepActive(email);
    MockHttpServletResponse last = refresh(second.refreshToken());
    assertThat(last.getStatus()).isEqualTo(200);
    Tokens third = tokens(last);
    // One second before the cap, the access token may live only that second too.
    JsonNode cappedClaims = claims(third.accessToken());
    assertThat(cappedClaims.get("exp").asLong() - cappedClaims.get("iat").asLong()).isEqualTo(1);
    assertThat(jsonMapper.readTree(last.getContentAsString()).get("expiresIn").asLong())
        .isEqualTo(1);

    clock.advance(Duration.ofSeconds(1));
    keepActive(email);
    String correlationId = UUID.randomUUID().toString();
    MockHttpServletResponse atCap =
        mvc.perform(
                browserPost("refresh")
                    .header(CorrelationId.HEADER, correlationId)
                    .cookie(new Cookie("refresh_token", third.refreshToken())))
            .andReturn()
            .getResponse();
    assertSame401(atCap);
    assertThat(auditReason("TOKEN_REFRESH", correlationId)).isEqualTo("EXPIRED_TOKEN");
  }
}
