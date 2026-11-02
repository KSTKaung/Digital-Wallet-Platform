package com.portfolio.wallet.notification.application;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Outgoing mail settings; the SMTP connection itself is under {@code spring.mail}. */
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record NotificationProperties(@NotBlank String from) {}
