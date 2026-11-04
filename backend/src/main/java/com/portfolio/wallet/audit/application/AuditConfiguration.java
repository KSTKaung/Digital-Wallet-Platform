package com.portfolio.wallet.audit.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link AuditProperties}. */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
class AuditConfiguration {}
