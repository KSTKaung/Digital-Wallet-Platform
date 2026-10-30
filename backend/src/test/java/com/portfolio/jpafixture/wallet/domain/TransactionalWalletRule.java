package com.portfolio.jpafixture.wallet.domain;

import org.springframework.transaction.annotation.Transactional;

/**
 * Violation: a financial domain class that uses a Spring annotation (documentations/06 MVP-ADR-07).
 * Not a stereotype, and outside {@code com.portfolio.wallet}, so component scanning never registers
 * it as a bean.
 */
@Transactional
public class TransactionalWalletRule {}
