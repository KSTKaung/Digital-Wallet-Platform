package com.portfolio.wallet.transfer.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link TransferIdempotencyProperties} and {@link TransferReviewProperties}. */
@Configuration
@EnableConfigurationProperties({
  TransferIdempotencyProperties.class,
  TransferReviewProperties.class
})
class TransferConfiguration {}
