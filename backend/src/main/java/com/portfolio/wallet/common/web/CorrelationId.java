package com.portfolio.wallet.common.web;

import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/** Correlation ID header and MDC key shared by filters, error handling and logs. */
public final class CorrelationId {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  /**
   * Accepted client values: 1–64 characters, letters, digits, '-', '_' or '.' (no log injection).
   */
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private CorrelationId() {}

  public static boolean isValid(String value) {
    return value != null && VALID.matcher(value).matches();
  }

  public static Optional<String> current() {
    return Optional.ofNullable(MDC.get(MDC_KEY));
  }
}
