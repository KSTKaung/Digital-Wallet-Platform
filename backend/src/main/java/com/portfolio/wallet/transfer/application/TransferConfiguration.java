package com.portfolio.wallet.transfer.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link TransferIdempotencyProperties}, {@link TransferReviewProperties} and {@link
 * TransferExpiryProperties}.
 */
@Configuration
@EnableConfigurationProperties({
  TransferIdempotencyProperties.class,
  TransferReviewProperties.class,
  TransferExpiryProperties.class
})
class TransferConfiguration {}
