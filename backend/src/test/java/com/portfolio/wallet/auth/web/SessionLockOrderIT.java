package com.portfolio.wallet.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.auth.application.KeyedHasher;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * Every path takes the session, then its tokens (documentations/06 MVP-ADR-23). These force the
 * interleavings in which token-then-session would deadlock: a connection holds a token row so the
 * revoking path stops there, holding the session, and the rotation queues behind it. Under the old
 * order the rotation would hold a token and wait for the session, and the database would abort one
 * of them, which the client would see as a 500.
 */
class SessionLockOrderIT extends AuthFlowSupport {

  @Autowired KeyedHasher hasher;

  private void lockToken(Connection holder, String refreshToken) throws Exception {
    queryOne(
        holder,
        "SELECT id::text FROM auth.refresh_tokens WHERE token_hash = ? FOR UPDATE",
        String.class,
        hasher.refreshToken(refreshToken));
  }

  private String sessionIdOf(String accessToken) throws Exception {
    return jsonMapper
        .readTree(
            new String(
                Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8))
        .get("sid")
        .asString();
  }

  private String revokedReason(String accessToken) throws Exception {
    return jdbc.queryForObject(
        "SELECT revoked_reason FROM auth.sessions WHERE id = ?::uuid",
        String.class,
        sessionIdOf(accessToken));
  }

  private int logout(String accessToken) throws Exception {
    return mvc.perform(
            browserPost("logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void logoutAndRotateNeitherDeadlockNorFail() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    try (ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection holder = openTransaction()) {
      lockToken(holder, tokens.refreshToken());

      Future<Integer> logout = pool.submit(() -> logout(tokens.accessToken()));
      awaitLockWaiters(holder, 1);
      Future<Integer> rotate = pool.submit(() -> refresh(tokens.refreshToken()).getStatus());
      awaitLockWaiters(holder, 2);
      holder.rollback();

      assertThat(logout.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(204);
      assertThat(rotate.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(401);
    }
    assertThat(revokedReason(tokens.accessToken())).isEqualTo("LOGOUT");
  }

  @Test
  void reuseAndRotateNeitherDeadlockNorFail() throws Exception {
    Tokens first = loginTokens(activeUser());
    Tokens second = tokens(refresh(first.refreshToken()));
    try (ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection holder = openTransaction()) {
      lockToken(holder, second.refreshToken());

      // Reuse of the old token revokes the session, then stops at the newer token.
      Future<Integer> reuse = pool.submit(() -> refresh(first.refreshToken()).getStatus());
      awaitLockWaiters(holder, 1);
      Future<Integer> rotate = pool.submit(() -> refresh(second.refreshToken()).getStatus());
      awaitLockWaiters(holder, 2);
      holder.rollback();

      assertThat(reuse.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(401);
      assertThat(rotate.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(401);
    }
    assertThat(revokedReason(first.accessToken())).isEqualTo("TOKEN_REUSE");
  }

  @Test
  void aRotationHoldingTheSessionMakesLogoutWaitAndThenWin() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    try (ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection holder = openTransaction()) {
      // The rotation waits for this row lock on the session, as does logout behind it.
      queryOne(
          holder,
          "SELECT id::text FROM auth.sessions WHERE id = ?::uuid FOR NO KEY UPDATE",
          String.class,
          sessionIdOf(tokens.accessToken()));

      Future<Integer> rotate = pool.submit(() -> refresh(tokens.refreshToken()).getStatus());
      awaitLockWaiters(holder, 1);
      Future<Integer> logout = pool.submit(() -> logout(tokens.accessToken()));
      awaitLockWaiters(holder, 2);
      holder.rollback();

      // Whichever gets the session first, each answers cleanly and logout has the last word.
      assertThat(rotate.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isIn(200, 401);
      assertThat(logout.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(204);
    }
    assertThat(revokedReason(tokens.accessToken())).isEqualTo("LOGOUT");
  }

  @Test
  void aSessionDeletedWhileARotationWaitsMakesItAnUnknownToken() throws Exception {
    Tokens tokens = loginTokens(activeUser());
    String sessionId = sessionIdOf(tokens.accessToken());
    try (ExecutorService pool = Executors.newFixedThreadPool(1);
        Connection holder = openTransaction()) {
      // What the purge does: the session goes, and its tokens with it by the foreign key.
      update(holder, "DELETE FROM auth.sessions WHERE id = ?::uuid", sessionId);

      Future<Integer> rotate = pool.submit(() -> refresh(tokens.refreshToken()).getStatus());
      awaitLockWaiters(holder, 1);
      holder.commit();

      assertThat(rotate.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(401);
    }
  }
}
