package com.portfolio.wallet.notification.application;

import java.util.List;
import java.util.UUID;

/**
 * Who receives a staff email meant for compliance officers (documentations/06 MVP-ADR-24 "Expiry":
 * after 12 hours, compliance officers are notified that a held transfer will expire; doc 08 §4C.3,
 * the port listing active {@code COMPLIANCE_OFFICER} user ids). A port beside {@link
 * RecipientAddresses}, for the same reason: the notification module must not depend on {@code
 * auth}, which already depends on it, and the module that writes the email (the transfer reminder)
 * then needs no dependency on {@code auth} either. {@code auth.application} implements it.
 *
 * <p>Only user ids: the outbox stores the id and looks the address up when it sends (MVP-ADR-25).
 */
public interface ComplianceOfficerRecipients {

  /**
   * @return the user ids of the {@code COMPLIANCE_OFFICER} accounts whose status is {@code ACTIVE}
   *     (the only status that can sign in), in ascending id order so that concurrent writers take
   *     the outbox's row locks in one order; empty if there is none
   * @throws org.springframework.dao.DataAccessException if the read fails
   */
  List<UUID> activeComplianceOfficers();
}
