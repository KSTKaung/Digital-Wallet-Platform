package com.portfolio.wallet.transfer.application;

import java.util.List;

/** One page of the transfer review queue (documentations/02 §1.5), {@code page} counted from 0. */
public record HeldTransferPage(
    List<HeldTransferView> items, int page, int size, long totalElements, long totalPages) {

  public HeldTransferPage {
    items = List.copyOf(items);
  }
}
