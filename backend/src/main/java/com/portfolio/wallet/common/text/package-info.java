/**
 * The free-text rules shared by every module that stores text a person typed: {@link
 * com.portfolio.wallet.common.text.TextRules} (documentations/06 MVP-ADR-10: the KYC reason text
 * rules, also applied to the transfer reviewer's reason, MVP-ADR-24 amendment Q10). The KYC fields
 * of the {@code user} module and the review reason of the {@code risk} module apply the same rules
 * from here, so they cannot drift apart; {@code risk} may not depend on {@code user} (ArchUnit
 * {@code onlyAdminDependsOnUser}).
 *
 * <p>Pure Java: no Spring, JPA or Hibernate, so domain packages can use it. It depends on no
 * module.
 */
package com.portfolio.wallet.common.text;
