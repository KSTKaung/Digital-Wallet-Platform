package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.common.web.ApiErrorController;
import com.portfolio.wallet.support.RejectingJwtDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The money-POST limit per customer (documentations/02 §1.8; doc 06 MVP-ADR-06) in the real filter
 * chain, with a small limit. No wallet controller is loaded: the limit runs before routing, so the
 * paths answer {@code 404} until it trips. Each test uses its own user.
 */
@WebMvcTest(
    controllers = ApiErrorController.class,
    properties = {
      "app.rate-limit.money-requests-per-minute-per-customer=2",
      "app.rate-limit.other-requests-per-minute-per-user=100"
    })
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class, RejectingJwtDecoder.class})
class MoneyRateLimitWebTest {

  private static final String DEPOSITS = "/api/v1/wallet/deposits";
  private static final String WITHDRAWALS = "/api/v1/wallet/withdrawals";
  private static final String TRANSFERS = "/api/v1/transfers";

  @Autowired MockMvc mvc;

  private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }

  @Test
  void depositsAndWithdrawalsShareOneCountPerCustomer() throws Exception {
    assertThat(send(post(DEPOSITS).with(user("erin"))).getStatus()).isNotEqualTo(429);
    assertThat(send(post(WITHDRAWALS).with(user("erin"))).getStatus()).isNotEqualTo(429);

    MockHttpServletResponse limited = send(post(DEPOSITS).with(user("erin")));
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    assertThat(limited.getContentAsString()).contains("\"errorCode\":\"RATE_LIMITED\"");

    // Another customer is not affected, and other requests of this one are not money POSTs.
    assertThat(send(post(DEPOSITS).with(user("frank"))).getStatus()).isNotEqualTo(429);
    assertThat(send(get("/api/v1/wallet").with(user("erin"))).getStatus()).isNotEqualTo(429);
    assertThat(send(get(DEPOSITS).with(user("erin"))).getStatus()).isNotEqualTo(429);
  }

  /**
   * Q15 (doc 08 §4C.2; doc 02 §1.8; doc 06 MVP-ADR-24 amendment): {@code POST /transfers} shares
   * the money count with deposits and withdrawals; the cancel and the reads count only toward the
   * general per-user limit.
   */
  @Test
  void transfersCountAsMoneyPostsAndCancelsDoNot() throws Exception {
    assertThat(send(post(TRANSFERS).with(user("gina"))).getStatus()).isNotEqualTo(429);
    assertThat(send(post(DEPOSITS).with(user("gina"))).getStatus()).isNotEqualTo(429);

    MockHttpServletResponse limited = send(post(TRANSFERS).with(user("gina")));
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    assertThat(send(post(WITHDRAWALS).with(user("gina"))).getStatus()).isEqualTo(429);

    // Not money POSTs: the cancel, the reads, and a trailing path segment.
    for (int i = 0; i < 4; i++) {
      assertThat(
              send(post(TRANSFERS + "/" + java.util.UUID.randomUUID() + "/cancel")
                      .with(user("gina")))
                  .getStatus())
          .isNotEqualTo(429);
      assertThat(send(get(TRANSFERS).with(user("gina"))).getStatus()).isNotEqualTo(429);
    }
    // Another customer's transfers have their own count.
    assertThat(send(post(TRANSFERS).with(user("hank"))).getStatus()).isNotEqualTo(429);
  }

  /**
   * Path variants of {@code POST /transfers} (code review, step 5): the trailing slash and an
   * encoded slash are not money paths here and are never served (TransferCreateFlowIT). A
   * percent-encoded unreserved character ({@code %74} = {@code t}) is decoded alike by the limiter
   * and by MVC routing: it is served as a transfer, and it is counted as a money POST.
   */
  @Test
  void pathVariantsOfTransfersCannotBypassTheMoneyCount() throws Exception {
    java.net.URI encoded = java.net.URI.create("/api/v1/%74ransfers");
    assertThat(send(post(encoded).with(user("ivy"))).getStatus()).isNotEqualTo(429);
    assertThat(send(post(TRANSFERS).with(user("ivy"))).getStatus()).isNotEqualTo(429);
    MockHttpServletResponse limited = send(post(encoded).with(user("ivy")));
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(send(post(TRANSFERS).with(user("ivy"))).getStatus()).isEqualTo(429);

    for (String variant : new String[] {TRANSFERS + "/", "/api/v1/transfers%2F"}) {
      for (int i = 0; i < 3; i++) {
        assertThat(send(post(java.net.URI.create(variant)).with(user("jack"))).getStatus())
            .as(variant)
            .isIn(400, 404, 405);
      }
    }
  }

  @Test
  void anonymousMoneyPostsAreJust401() throws Exception {
    for (int i = 0; i < 4; i++) {
      assertThat(send(post(WITHDRAWALS)).getStatus()).isEqualTo(401);
    }
  }
}
