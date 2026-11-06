/**
 * The hashing core of request fingerprints (documentations/02 §1.4; doc 07 §7): {@link
 * com.portfolio.wallet.common.fingerprint.CanonicalForm} writes a module's canonical form of a
 * validated request and its SHA-256. The domain fingerprints of the money modules ({@code
 * wallet.domain.RequestFingerprint}, {@code transfer.domain.TransferRequestFingerprint}) build on
 * it, so this package is pure Java: no Spring, JPA or Hibernate (ArchUnit {@code
 * financialDomainIsFrameworkFree}; doc 06 MVP-ADR-07). It depends on no module.
 */
package com.portfolio.wallet.common.fingerprint;
