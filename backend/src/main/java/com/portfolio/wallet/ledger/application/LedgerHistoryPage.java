package com.portfolio.wallet.ledger.application;

import java.util.List;

/** One page of a customer account's entries, newest first, {@code page} counted from 0. */
public record LedgerHistoryPage(
    List<LedgerHistoryItem> items, int page, int size, long totalElements, long totalPages) {

  public LedgerHistoryPage {
    items = List.copyOf(items);
  }
}
