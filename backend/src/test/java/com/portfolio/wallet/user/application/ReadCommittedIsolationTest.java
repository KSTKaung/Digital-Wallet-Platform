package com.portfolio.wallet.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * V8's profile lock relies on READ COMMITTED (owner, 2026-11-04, N4), so the three paths that take
 * it set the level explicitly instead of depending on a default. {@code UserConstraintsIT} checks
 * the database default too.
 */
class ReadCommittedIsolationTest {

  @ParameterizedTest(name = "{0}.{1}")
  @CsvSource({
    "CustomerProfileService, updateProfile",
    "KycApplicationService, submit",
    "KycReviewService, approve"
  })
  void theProfileLockPathsRunInReadCommitted(String service, String method) throws Exception {
    Class<?> type = Class.forName(getClass().getPackageName() + "." + service);
    Method target =
        Arrays.stream(type.getMethods())
            .filter(m -> m.getName().equals(method))
            .findFirst()
            .orElseThrow();

    assertThat(target.getAnnotation(Transactional.class).isolation())
        .isEqualTo(Isolation.READ_COMMITTED);
  }
}
