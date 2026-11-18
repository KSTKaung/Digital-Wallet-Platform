package com.portfolio.wallet.admin.application;

import java.util.List;

/** One page of the transfer review queue (documentations/02 §1.5); {@code page} counts from 0. */
public record TransferReviewPage(
    List<TransferReviewSummary> items, int page, int size, long totalElements, long totalPages) {

  public TransferReviewPage {
    items = List.copyOf(items);
  }
}
