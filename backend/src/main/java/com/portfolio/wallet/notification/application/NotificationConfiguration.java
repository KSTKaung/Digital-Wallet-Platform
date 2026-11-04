package com.portfolio.wallet.notification.application;

import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers the outgoing mail settings and the email outbox's retry policy, and checks at startup
 * that the outbox lease covers a send (R3, {@link OutboxLeaseCheck}): the context does not start
 * otherwise.
 */
@Configuration
@EnableConfigurationProperties({NotificationProperties.class, OutboxProperties.class})
public class NotificationConfiguration {

  @Bean
  OutboxLeaseCheck outboxLeaseCheck(OutboxProperties outbox, Environment environment) {
    Binder binder = Binder.get(environment);
    Map<String, String> mail =
        binder
            .bind("spring.mail.properties", Bindable.mapOf(String.class, String.class))
            .orElse(Map.of());
    return OutboxLeaseCheck.verify(
        outbox,
        binder.bind("spring.mail.protocol", String.class).orElse(null),
        binder.bind("spring.mail.jndi-name", String.class).orElse(null),
        mail);
  }
}
