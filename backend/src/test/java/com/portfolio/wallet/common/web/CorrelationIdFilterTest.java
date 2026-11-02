package com.portfolio.wallet.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.common.correlation.CorrelationId;
import jakarta.servlet.DispatcherType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void generatesIdWhenHeaderIsAbsent() throws Exception {
    var response = new MockHttpServletResponse();
    var seenInMdc = new AtomicReference<String>();

    filter.doFilter(
        new MockHttpServletRequest(),
        response,
        (req, res) -> seenInMdc.set(MDC.get(CorrelationId.MDC_KEY)));

    String header = response.getHeader(CorrelationId.HEADER);
    assertThat(UUID.fromString(header)).isNotNull();
    assertThat(seenInMdc.get()).isEqualTo(header);
  }

  @Test
  void preservesValidIncomingId() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationId.HEADER, "client-123.abc_9");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("client-123.abc_9");
  }

  /** The first pass has cleared the MDC by then, and the error body needs the same id. */
  @Test
  void errorDispatchReusesTheIdOfTheOriginalRequest() throws Exception {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    String generated = response.getHeader(CorrelationId.HEADER);
    var seenInMdc = new AtomicReference<String>();

    request.setDispatcherType(DispatcherType.ERROR);
    filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get(CorrelationId.MDC_KEY)));

    assertThat(seenInMdc.get()).isEqualTo(generated);
    assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(generated);
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  void replacesIdThatCouldInjectIntoLogs() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationId.HEADER, "abc\nlevel=ERROR forged");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    String header = response.getHeader(CorrelationId.HEADER);
    assertThat(header).doesNotContain("forged");
    assertThat(UUID.fromString(header)).isNotNull();
  }

  @Test
  void replacesOverlongId() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationId.HEADER, "a".repeat(65));
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader(CorrelationId.HEADER)).hasSize(36);
  }

  @Test
  void clearsMdcAfterTheRequest() throws Exception {
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {});

    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }
}
