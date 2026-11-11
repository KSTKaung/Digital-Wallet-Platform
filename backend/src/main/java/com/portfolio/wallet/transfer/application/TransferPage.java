package com.portfolio.wallet.transfer.application;

import java.util.List;

/** One page of {@code GET /transfers} (documentations/02 §1.5), {@code page} counted from 0. */
public record TransferPage(
    List<TransferView> items, int page, int size, long totalElements, long totalPages) {

  public TransferPage {
    items = List.copyOf(items);
  }
}
