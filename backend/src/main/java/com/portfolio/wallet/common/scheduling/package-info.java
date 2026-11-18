/**
 * The scheduler of the application's {@code @Scheduled} jobs: the email outbox, the transfer
 * expiry, the 12-hour review reminder, the reconciliation, and the purges (auth records no rule
 * needs any more, deposit and withdrawal idempotency keys, transfer idempotency keys, and
 * wallet-lookup attempts). No table and no module dependency belongs to this package.
 */
package com.portfolio.wallet.common.scheduling;
