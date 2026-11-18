package com.portfolio.wallet.admin.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link ReconciliationProperties}. */
@Configuration
@EnableConfigurationProperties(ReconciliationProperties.class)
class AdminConfiguration {}
