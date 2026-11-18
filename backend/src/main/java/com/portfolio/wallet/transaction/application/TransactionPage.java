package com.portfolio.wallet.transaction.application;

import java.util.List;

/** One page of {@code GET /transactions} (documentations/02 §1.5), {@code page} counted from 0. */
public record TransactionPage(
    List<TransactionView> items, int page, int size, long totalElements, long totalPages) {

  public TransactionPage {
    items = List.copyOf(items);
  }
}
