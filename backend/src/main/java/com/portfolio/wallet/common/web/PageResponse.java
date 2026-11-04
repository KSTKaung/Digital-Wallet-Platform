package com.portfolio.wallet.common.web;

import java.util.List;

/**
 * A page of a list endpoint (documentations/02 §1.5): {@code {items, page: {page, size,
 * totalElements, totalPages}}}, with {@code page} counted from 0.
 */
public record PageResponse<T>(List<T> items, PageInfo page) {

  public PageResponse {
    items = List.copyOf(items);
  }

  public record PageInfo(int page, int size, long totalElements, long totalPages) {}

  public static <T> PageResponse<T> of(
      List<T> items, int page, int size, long totalElements, long totalPages) {
    return new PageResponse<>(items, new PageInfo(page, size, totalElements, totalPages));
  }
}
