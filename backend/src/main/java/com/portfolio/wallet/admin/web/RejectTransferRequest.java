package com.portfolio.wallet.admin.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /admin/transfer-reviews/{transferId}/reject} (documentations/02 §2.7). The
 * reason is staff-only (MVP-ADR-24 Q10): it is stored in {@code risk.risk_reviews.reason} only.
 * Deliberately without Bean Validation: the review deadline is checked before the reason (doc 08
 * §5, step 3 notes), so the service checks it (required, at most 500 characters, the MVP-ADR-10
 * reason text rules) only when the transfer is still open. {@link #toString()} leaves it out.
 */
record RejectTransferRequest(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 500,
            description =
                "Staff-only, never shown to the customer. Required before the deadline: at most"
                    + " 500 characters, a letter or digit, no control or format characters.")
        String reason) {

  @Override
  public String toString() {
    return "RejectTransferRequest[]";
  }
}
