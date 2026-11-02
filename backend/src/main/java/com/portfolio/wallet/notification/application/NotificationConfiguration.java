package com.portfolio.wallet.notification.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the outgoing mail settings. */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfiguration {}
