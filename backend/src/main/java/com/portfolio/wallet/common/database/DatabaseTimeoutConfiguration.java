package com.portfolio.wallet.common.database;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link DatabaseTimeoutProperties}. */
@Configuration
@EnableConfigurationProperties(DatabaseTimeoutProperties.class)
class DatabaseTimeoutConfiguration {}
