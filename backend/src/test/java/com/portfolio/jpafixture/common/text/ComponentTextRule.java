package com.portfolio.jpafixture.common.text;

import org.springframework.transaction.annotation.Transactional;

/**
 * Violation: a class of the shared free-text rules ({@code common.text}, used by {@code
 * risk.domain}) that uses a Spring annotation (documentations/06 MVP-ADR-07). Outside {@code
 * com.portfolio.wallet}, so component scanning never registers it.
 */
@Transactional
public class ComponentTextRule {}
