package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * The per-scope "already filtered" marker of {@link RateLimitFilter} (PR 4b step 8 fix): two
 * limiters whose paths overlap (the per-user and the money limit, doc 02 §1.8) are both enforced,
 * each with its own marker, while one limiter still counts a request once.
 */
class RateLimitFilterTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);
  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  private static final Function<HttpServletRequest, String> SAME_USER = request -> "user-1";
  private static final String PREFIX = RateLimitFilter.class.getName();

  private final JsonSecurityErrorHandler errors =
      new JsonSecurityErrorHandler(
          JSON, new StaticListableBeanFactory().getBeanProvider(AccessDenialAuditor.class));

  private RateLimitFilter filter(String scope, int limit) {
    return filter(scope, AnyRequestMatcher.INSTANCE, limit);
  }

  private RateLimitFilter filter(String scope, RequestMatcher paths, int limit) {
    return new RateLimitFilter(
        scope, paths, SAME_USER, new RateLimiter(limit, Duration.ofMinutes(1), 100, CLOCK), errors);
  }

  /**
   * One request through {@code filters}. {@code markers} are the rate-limit markers the endpoint
   * saw (each filter removes its own when the chain returns), or null if it was not reached.
   */
  private record Outcome(MockHttpServletResponse response, List<String> markers) {}

  private static Outcome send(Filter... filters) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/wallet/deposits");
    MockHttpServletResponse response = new MockHttpServletResponse();
    List<List<String>> seen = new ArrayList<>();
    HttpServlet endpoint =
        new HttpServlet() {
          @Override
          protected void service(HttpServletRequest req, HttpServletResponse res) {
            seen.add(markers(req));
          }
        };
    new MockFilterChain(endpoint, filters).doFilter(request, response);
    return new Outcome(response, seen.isEmpty() ? null : seen.get(0));
  }

  private static List<String> markers(HttpServletRequest request) {
    return Collections.list(request.getAttributeNames()).stream()
        .filter(name -> name.startsWith(PREFIX))
        .sorted()
        .toList();
  }

  @Test
  void theSecondOfTwoOverlappingLimitersIsEnforced() throws Exception {
    RateLimitFilter perUser = filter("user", 100);
    RateLimitFilter money = filter("money", 2);

    assertThat(send(perUser, money).markers()).isNotNull();
    assertThat(send(perUser, money).markers()).isNotNull();
    Outcome third = send(perUser, money);

    // Before the fix both filters shared one marker, so the money filter never ran.
    assertThat(third.markers()).isNull();
    assertThat(third.response().getStatus()).isEqualTo(429);
    assertThat(third.response().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    assertThat(third.response().getContentAsString()).contains("\"errorCode\":\"RATE_LIMITED\"");
  }

  @Test
  void theFirstOfTwoOverlappingLimitersIsEnforcedToo() throws Exception {
    RateLimitFilter perUser = filter("user", 2);
    RateLimitFilter money = filter("money", 100);

    send(perUser, money);
    send(perUser, money);
    Outcome third = send(perUser, money);

    assertThat(third.markers()).isNull();
    assertThat(third.response().getStatus()).isEqualTo(429);
  }

  @Test
  void eachScopeSetsItsOwnMarker() throws Exception {
    Outcome outcome = send(filter("user", 100), filter("money", 100));

    assertThat(outcome.markers())
        .containsExactly(PREFIX + ".money.FILTERED", PREFIX + ".user.FILTERED");
  }

  /** A single limiter is unchanged: met twice on one request, it counts the request once. */
  @Test
  void oneLimiterCountsARequestOnceEvenIfItRunsTwice() throws Exception {
    RateLimitFilter perUser = filter("user", 2);

    Outcome first = send(perUser, perUser);
    Outcome second = send(perUser, perUser);

    assertThat(first.markers()).containsExactly(PREFIX + ".user.FILTERED");
    assertThat(second.markers()).containsExactly(PREFIX + ".user.FILTERED");
    assertThat(send(perUser).response().getStatus()).isEqualTo(429);
  }

  /** The money limit counts only its own paths; the per-user limit counts everything. */
  @Test
  void aRequestOutsideTheMoneyPathsIsCountedOnlyByThePerUserLimit() throws Exception {
    RequestMatcher noPath = request -> false;
    RateLimitFilter perUser = filter("user", 100);
    RateLimitFilter money = filter("money", noPath, 1);

    for (int i = 0; i < 3; i++) {
      Outcome outcome = send(perUser, money);
      assertThat(outcome.markers()).containsExactly(PREFIX + ".user.FILTERED");
    }
  }

  @Test
  void aRequestWithoutAKeyIsNotLimited() throws Exception {
    RateLimitFilter anonymous =
        new RateLimitFilter(
            "user",
            AnyRequestMatcher.INSTANCE,
            request -> null,
            new RateLimiter(1, Duration.ofMinutes(1), 100, CLOCK),
            errors);

    for (int i = 0; i < 3; i++) {
      assertThat(send(anonymous).markers()).isNotNull();
    }
  }
}
