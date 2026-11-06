package com.portfolio.wallet.transfer.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link TransferIdempotencyProperties}. */
@Configuration
@EnableConfigurationProperties(TransferIdempotencyProperties.class)
class TransferConfiguration {}
