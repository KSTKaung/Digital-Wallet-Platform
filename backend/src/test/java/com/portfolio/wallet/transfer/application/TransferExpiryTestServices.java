package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import java.time.Duration;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Test only: a {@link TransferExpiryService} over the context's real beans (and spies) with its own
 * per-run maximum, so an integration test can use a small maximum without a second Spring context.
 */
public final class TransferExpiryTestServices {

  private TransferExpiryTestServices() {}

  public static TransferExpiryService withMaxPerRun(ApplicationContext context, int maxPerRun) {
    return new TransferExpiryService(
        context.getBean(TransferRepository.class),
        context.getBean(HeldTransferDecider.class),
        context.getBean(TransactionTimeouts.class),
        new TransferExpiryProperties(false, Duration.ofMinutes(1), maxPerRun),
        context.getBean(PlatformTransactionManager.class));
  }
}
