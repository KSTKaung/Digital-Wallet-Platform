package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An outbox row under a caller-chosen event id against PostgreSQL (doc 08 §4C.3: reminder
 * deduplication; documentations/06 MVP-ADR-24 "Jobs (Q11)"): written at most once; a duplicate is
 * no error and leaves the caller's transaction usable; a concurrent writer of the same id waits and
 * then writes nothing, or writes it if the first rolled back; the existence read sees committed
 * rows and reads any number of ids.
 */
class NotificationOutboxEnqueueOnceIT extends DatabaseIntegrationTest {

  private static final EmailKind KIND = EmailKind.TRANSFER_REVIEW_EXPIRING;

  @Autowired NotificationOutbox outbox;
  @Autowired PlatformTransactionManager transactionManager;

  private TransactionTemplate transactions() {
    return new TransactionTemplate(transactionManager);
  }

  private static Map<String, String> values(String reference) {
    return MoneyEmailValues.of(new BigDecimal("1300.00"), "THB", reference);
  }

  private boolean enqueueOnce(UUID event, UUID user, String reference) {
    return Boolean.TRUE.equals(
        transactions().execute(s -> outbox.enqueueOnce(event, user, KIND, values(reference))));
  }

  private List<Map<String, Object>> rows(UUID event) {
    return jdbc.queryForList(
        "SELECT user_id, kind, status, payload ->> 'reference' AS reference"
            + " FROM notification.notifications WHERE event_id = ?",
        event);
  }

  @Test
  void theSameIdIsWrittenOnceAndTheFirstRowStays() throws Exception {
    UUID user = newUser();
    UUID event = UUID.randomUUID();

    assertThat(enqueueOnce(event, user, "TRF-A1")).isTrue();
    assertThat(enqueueOnce(event, user, "TRF-B2")).isFalse();

    assertThat(rows(event))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .containsEntry("user_id", user)
                    .containsEntry("kind", KIND.name())
                    .containsEntry("status", "PENDING")
                    .containsEntry("reference", "TRF-A1"));
  }

  /** No unique violation aborts the transaction: the next statement in it still commits. */
  @Test
  void aDuplicateLeavesTheCallersTransactionUsable() throws Exception {
    UUID user = newUser();
    UUID existing = UUID.randomUUID();
    assertThat(enqueueOnce(existing, user, "TRF-C3")).isTrue();
    UUID fresh = UUID.randomUUID();

    List<Boolean> written =
        transactions()
            .execute(
                s ->
                    List.of(
                        outbox.enqueueOnce(existing, user, KIND, values("TRF-C3")),
                        outbox.enqueueOnce(fresh, user, KIND, values("TRF-C3"))));

    assertThat(written).containsExactly(false, true);
    assertThat(rows(fresh)).hasSize(1);
    assertThat(rows(existing)).hasSize(1);
  }

  /**
   * A second writer of the same id waits for the first transaction: nothing if it commits, the row
   * if it rolls back. Neither raises an error.
   */
  @Test
  void aConcurrentWriterOfTheSameIdWaitsThenWritesNothingOrTheRow() throws Exception {
    for (boolean firstCommits : new boolean[] {true, false}) {
      UUID user = newUser();
      UUID event = UUID.randomUUID();
      CountDownLatch inserted = new CountDownLatch(1);
      CountDownLatch finish = new CountDownLatch(1);
      int[] firstPid = {0};
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Boolean> first =
            pool.submit(
                () ->
                    transactions()
                        .execute(
                            s -> {
                              boolean wrote =
                                  outbox.enqueueOnce(event, user, KIND, values("TRF-D4"));
                              firstPid[0] =
                                  jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                              inserted.countDown();
                              try {
                                assertThat(finish.await(30, TimeUnit.SECONDS)).isTrue();
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                              if (!firstCommits) {
                                s.setRollbackOnly();
                              }
                              return wrote;
                            }));
        assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
        Future<Boolean> second = pool.submit(() -> enqueueOnce(event, user, "TRF-E5"));
        awaitBlockedBy(firstPid[0], 1);
        finish.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(!firstCommits);
      } finally {
        pool.shutdownNow();
      }
      assertThat(rows(event))
          .singleElement()
          .satisfies(
              row ->
                  assertThat(row).containsEntry("reference", firstCommits ? "TRF-D4" : "TRF-E5"));
    }
  }

  @Test
  void recordedFindsTheCommittedIdsAmongAnyNumber() throws Exception {
    UUID user = newUser();
    UUID first = UUID.randomUUID();
    UUID last = UUID.randomUUID();
    assertThat(enqueueOnce(first, user, "TRF-F6")).isTrue();
    assertThat(enqueueOnce(last, user, "TRF-F6")).isTrue();
    List<UUID> asked = new ArrayList<>();
    asked.add(first);
    for (int i = 0; i < 2500; i++) {
      asked.add(UUID.randomUUID());
    }
    asked.add(last);
    asked.add(first);

    assertThat(outbox.recorded(asked)).containsExactlyInAnyOrder(first, last);
    assertThat(outbox.recorded(List.of())).isEmpty();
  }
}
