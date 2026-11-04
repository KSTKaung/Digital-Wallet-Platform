package com.portfolio.wallet.user.application;

import java.util.List;

/** One page of the staff list (documentations/02 §1.5); {@code page} counts from 0. */
public record KycReviewPage(List<KycReviewSummary> items, int page, int size, long totalElements) {

  public KycReviewPage {
    items = List.copyOf(items);
  }

  public long totalPages() {
    return (totalElements + size - 1) / size;
  }
}
