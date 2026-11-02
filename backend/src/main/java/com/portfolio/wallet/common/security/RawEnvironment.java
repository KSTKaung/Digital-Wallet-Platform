package com.portfolio.wallet.common.security;

import java.util.function.UnaryOperator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Reads secret values exactly as they were given. {@code Environment.getProperty} resolves {@code
 * ${...}} inside a value, so a secret containing that text could change silently, or pull another
 * property in, and a failure could print it. Walks the property sources in order instead, which
 * covers the environment variables and also whatever a test supplies.
 */
public final class RawEnvironment {

  private RawEnvironment() {}

  public static UnaryOperator<String> lookup(ConfigurableEnvironment environment) {
    return name -> {
      for (PropertySource<?> source : environment.getPropertySources()) {
        Object value = source.getProperty(name);
        if (value != null) {
          return value.toString();
        }
      }
      return null;
    };
  }
}
