package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.TestSecrets;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** A Spring context does not start without each secret (documentations/08 §4A.5 test case 10). */
class RequiredSecretsWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(RequiredSecretsConfiguration.class);

  private static String[] propertiesWithout(String missing) {
    return TestSecrets.values().entrySet().stream()
        .filter(secret -> !secret.getKey().equals(missing))
        .map(secret -> secret.getKey() + "=" + secret.getValue())
        .toArray(String[]::new);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        RequiredSecrets.JWT_PRIVATE_KEY,
        RequiredSecrets.JWT_KID,
        RequiredSecrets.OTP_HMAC_SECRET,
        RequiredSecrets.REFRESH_TOKEN_PEPPER
      })
  void contextFailsNamingTheMissingVariableWithoutPrintingAnyValue(String missing) {
    runner
        .withPropertyValues(propertiesWithout(missing))
        .run(
            context -> {
              assertThat(context).hasFailed();
              StringWriter trace = new StringWriter();
              context.getStartupFailure().printStackTrace(new PrintWriter(trace));
              assertThat(trace.toString()).contains(missing);
              TestSecrets.values()
                  .values()
                  .forEach(value -> assertThat(trace.toString()).doesNotContain(value));
            });
  }

  /** Resolved, this would be the valid kid in "other"; taken literally it is refused, unprinted. */
  @Test
  void aPlaceholderInASecretIsNotResolved() {
    runner
        .withPropertyValues(propertiesWithout(RequiredSecrets.JWT_KID))
        .withPropertyValues("other=resolved-kid", RequiredSecrets.JWT_KID + "=${other}")
        .run(
            context -> {
              assertThat(context).hasFailed();
              StringWriter trace = new StringWriter();
              context.getStartupFailure().printStackTrace(new PrintWriter(trace));
              assertThat(trace.toString())
                  .contains(RequiredSecrets.JWT_KID)
                  .doesNotContain("${other}")
                  .doesNotContain("resolved-kid");
            });
  }

  @Test
  void contextStartsWithAllSecrets() {
    runner
        .withPropertyValues(propertiesWithout("none"))
        .run(context -> assertThat(context).hasSingleBean(RequiredSecrets.class));
  }
}
