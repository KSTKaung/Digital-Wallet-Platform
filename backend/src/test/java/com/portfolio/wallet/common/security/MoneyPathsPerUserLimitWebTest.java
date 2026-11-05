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

/**
 * The counterpart of {@link MoneyRateLimitWebTest}: with the per-user limit lower than the money
 * limit, money POSTs are still counted by the per-user limit (doc 02 §1.8: the money limit is "in
 * addition to the general per-user limit"), and a money POST uses up the same per-user count as any
 * other request.
 */
@WebMvcTest(
    controllers = ApiErrorController.class,
    properties = {
      "app.rate-limit.money-requests-per-minute-per-customer=100",
      "app.rate-limit.other-requests-per-minute-per-user=3"
    })
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class, RejectingJwtDecoder.class})
class MoneyPathsPerUserLimitWebTest {

  @Autowired MockMvc mvc;

  private MockHttpServletResponse send(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }

  @Test
  void thePerUserLimitAlsoCountsMoneyPosts() throws Exception {
    assertThat(send(post("/api/v1/wallet/deposits").with(user("gina"))).getStatus())
        .isNotEqualTo(429);
    assertThat(send(get("/api/v1/wallet").with(user("gina"))).getStatus()).isNotEqualTo(429);
    assertThat(send(post("/api/v1/wallet/withdrawals").with(user("gina"))).getStatus())
        .isNotEqualTo(429);

    MockHttpServletResponse limited = send(post("/api/v1/wallet/deposits").with(user("gina")));
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
    assertThat(limited.getContentAsString()).contains("\"errorCode\":\"RATE_LIMITED\"");
    assertThat(send(get("/api/v1/wallet").with(user("gina"))).getStatus()).isEqualTo(429);

    assertThat(send(post("/api/v1/wallet/deposits").with(user("hugo"))).getStatus())
        .isNotEqualTo(429);
  }
}
