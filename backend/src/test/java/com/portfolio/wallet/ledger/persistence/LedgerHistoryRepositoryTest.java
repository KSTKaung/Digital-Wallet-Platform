package com.portfolio.wallet.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Pins the history page's order (documentations/06 MVP-ADR-11 amendment: newest first, with a
 * deterministic tie-break). The backward scan of V2's {@code ix_ledger_entries_account_created}
 * already yields {@code id DESC} within a tie, so the end-to-end test alone cannot show that the
 * clause is there; {@code transfer.web.TransactionHistoryIT} also checks the order with index scans
 * disabled.
 */
class LedgerHistoryRepositoryTest {

  private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
  private final LedgerHistoryRepository repository = new LedgerHistoryRepository(jdbc);

  @Test
  void thePageIsNewestFirstWithTheEntryIdAsTieBreakOnTheCallersAccountOnly() {
    repository.pageEntries(UUID.randomUUID(), 20, 40);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).sql(sql.capture());
    String query = sql.getValue().replaceAll("\\s+", " ");
    assertThat(query)
        .contains("WHERE e.account_id = :account")
        .contains("ORDER BY e.created_at DESC, e.id DESC")
        .contains("LIMIT :limit OFFSET :offset")
        .doesNotContain("FOR UPDATE")
        .doesNotContain("FOR SHARE");
  }

  @Test
  void theCountIsOfTheSameAccountsEntries() {
    // The deep stub cannot produce a count; only the statement is of interest.
    catchThrowable(() -> repository.countEntries(UUID.randomUUID()));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).sql(sql.capture());
    assertThat(sql.getValue())
        .isEqualTo("SELECT count(*) FROM ledger.ledger_entries WHERE account_id = :account");
  }
}
