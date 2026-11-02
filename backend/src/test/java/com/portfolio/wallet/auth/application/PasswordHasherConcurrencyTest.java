package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * At most {@code max-concurrent-hashes} hashes at once, dummy hashes included (documentations/06
 * MVP-ADR-23). No sleeps and no timing assertions: the encoder is replaced by one that holds every
 * caller until all the workers are parked, either on the hasher's bound or inside the encoder. At
 * that point the number inside cannot grow any more, so the peak it records is exact.
 */
class PasswordHasherConcurrencyTest {

  private static final AuthProperties PROPERTIES = AuthTestFixtures.PROPERTIES;
  private static final int LIMIT = PROPERTIES.argon2().maxConcurrentHashes();
  private static final int WORKERS = LIMIT * 6;
  private static final Duration SAFETY_TIMEOUT = Duration.ofSeconds(60);

  /** Stands in for the Argon2 computation. */
  private static final class RecordingWork implements Answer<Object> {
    private final CountDownLatch gate = new CountDownLatch(1);
    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    // The constructor computes the dummy hash once, before there is any concurrency to measure.
    private volatile boolean armed;

    @Override
    public Object answer(InvocationOnMock invocation) throws InterruptedException {
      boolean hashing = List.of("encode", "matches").contains(invocation.getMethod().getName());
      if (armed && hashing) {
        work();
      }
      return invocation.getMethod().getReturnType() == boolean.class ? Boolean.FALSE : "$stub";
    }

    void work() throws InterruptedException {
      peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
      calls.incrementAndGet();
      try {
        assertThat(gate.await(SAFETY_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
      } finally {
        inside.decrementAndGet();
      }
    }
  }

  /** Runs the tasks at once and returns once all have finished, rethrowing any failure. */
  private static void runHeldAtTheGate(RecordingWork work, int expectedInside, List<Task> tasks)
      throws Exception {
    work.armed = true;
    List<FutureTask<Void>> futures = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    for (Task task : tasks) {
      FutureTask<Void> future =
          new FutureTask<>(
              () -> {
                task.run();
                return null;
              });
      futures.add(future);
      threads.add(new Thread(future));
    }
    threads.forEach(Thread::start);

    await().atMost(SAFETY_TIMEOUT).until(() -> work.inside.get() >= expectedInside);
    await()
        .atMost(SAFETY_TIMEOUT)
        .until(() -> threads.stream().allMatch(PasswordHasherConcurrencyTest::isParkedOrDone));
    work.gate.countDown();

    for (FutureTask<Void> future : futures) {
      future.get(SAFETY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
  }

  private static boolean isParkedOrDone(Thread thread) {
    Thread.State state = thread.getState();
    return state == Thread.State.WAITING
        || state == Thread.State.TIMED_WAITING
        || state == Thread.State.TERMINATED;
  }

  @FunctionalInterface
  private interface Task {
    void run() throws Exception;
  }

  @Test
  void mixedHashesVerificationsAndDummiesNeverExceedTheBound() throws Exception {
    RecordingWork work = new RecordingWork();
    try (MockedConstruction<Argon2PasswordEncoder> ignored = encoderReplacedBy(work)) {
      PasswordHasher hasher = new PasswordHasher(PROPERTIES);
      List<Task> tasks = new ArrayList<>();
      for (int i = 0; i < WORKERS; i++) {
        int kind = i % 3;
        tasks.add(
            () -> {
              switch (kind) {
                case 0 -> hasher.hash("a-long-password");
                case 1 -> hasher.matches("a-long-password", "$stored");
                default -> hasher.dummyVerify("a-long-password");
              }
            });
      }

      runHeldAtTheGate(work, LIMIT, tasks);
    }

    assertThat(work.peak).hasValue(LIMIT);
    assertThat(work.calls).hasValue(WORKERS);
  }

  @Test
  void dummyHashesAloneNeverExceedTheBound() throws Exception {
    RecordingWork work = new RecordingWork();
    try (MockedConstruction<Argon2PasswordEncoder> ignored = encoderReplacedBy(work)) {
      PasswordHasher hasher = new PasswordHasher(PROPERTIES);
      List<Task> tasks = new ArrayList<>();
      for (int i = 0; i < WORKERS; i++) {
        tasks.add(() -> hasher.dummyVerify("a-long-password"));
      }

      runHeldAtTheGate(work, LIMIT, tasks);
    }

    assertThat(work.peak).hasValue(LIMIT);
    assertThat(work.calls).hasValue(WORKERS);
  }

  /** Without a bound the same measurement sees every worker, so the tests above can fail. */
  @Test
  void theMeasurementSeesEveryWorkerWhenNothingBoundsThem() throws Exception {
    RecordingWork work = new RecordingWork();
    List<Task> tasks = new ArrayList<>();
    for (int i = 0; i < WORKERS; i++) {
      tasks.add(work::work);
    }

    runHeldAtTheGate(work, WORKERS, tasks);

    assertThat(work.peak).hasValue(WORKERS);
  }

  private static MockedConstruction<Argon2PasswordEncoder> encoderReplacedBy(RecordingWork work) {
    return mockConstruction(Argon2PasswordEncoder.class, withSettings().defaultAnswer(work));
  }
}
