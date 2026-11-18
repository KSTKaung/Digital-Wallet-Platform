package com.portfolio.wallet.admin.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The result of one reconciliation run.
 *
 * @param completed whether every read and comparison finished; {@code false} after a failure, with
 *     no mismatches (a partial comparison would report false ones)
 * @param mismatches every mismatch, in check order
 * @param rowsRead how many rows each read returned, by name (for the run summary)
 */
public record ReconciliationReport(
    boolean completed, List<ReconciliationMismatch> mismatches, Map<String, Integer> rowsRead) {

  public ReconciliationReport {
    mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches"));
    rowsRead = Map.copyOf(Objects.requireNonNull(rowsRead, "rowsRead"));
  }

  static ReconciliationReport failed() {
    return new ReconciliationReport(false, List.of(), Map.of());
  }

  /** The mismatches whose identifiers include {@code id}. */
  public List<ReconciliationMismatch> involving(Object id) {
    return mismatches.stream().filter(mismatch -> mismatch.involves(id)).toList();
  }

  /** How many mismatches each check found, by check, sorted ({@code 1}–{@code 5}, {@code SUM}). */
  public Map<String, Long> countsByCheck() {
    return mismatches.stream()
        .map(ReconciliationMismatch::check)
        .collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()));
  }
}
