package com.portfolio.wallet.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The scheduler of the {@code @Scheduled} jobs (owner decision, PR 5a step 6 security review L2):
 * the configured pool size, Boot's own scheduler backing off even with virtual threads on, two jobs
 * running side by side while one is blocked, a job never overlapping itself, a graceful shutdown,
 * and a pool size of 0 refused at startup.
 */
class SchedulingConfigurationTest {

  private static final long WAIT_SECONDS = 10;

  /** As the application runs: virtual threads on, Boot's task scheduling auto-configuration. */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  TaskSchedulingAutoConfiguration.class, TaskExecutionAutoConfiguration.class))
          .withUserConfiguration(SchedulingConfiguration.class)
          .withPropertyValues(
              "spring.threads.virtual.enabled=true",
              "spring.task.execution.mode=force",
              "app.scheduling.pool-size=2",
              "app.scheduling.await-termination=30s");

  @Test
  void theOnlySchedulerIsAPoolOfTheConfiguredSizeThatShutsDownGracefully() {
    runner
        .withUserConfiguration(SchedulingOn.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(TaskScheduler.class);
              ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
              assertThat(context.getBean("taskScheduler")).isSameAs(scheduler);
              assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
              assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
              // Boot's application executor stays, apart from the scheduler (mode force).
              assertThat(context.getBean("applicationTaskExecutor"))
                  .isInstanceOf(SimpleAsyncTaskExecutor.class)
                  .isNotSameAs(scheduler);
            });
  }

  @Test
  void theSettingsAreAppliedToTheScheduler() {
    ThreadPoolTaskScheduler scheduler =
        SchedulingConfiguration.newScheduler(new SchedulingProperties(3, Duration.ofSeconds(7)));
    scheduler.initialize();
    try {
      assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
    } finally {
      scheduler.shutdown();
    }
  }

  /** L2: a blocked job does not keep another job from running; both run on the pool's threads. */
  @Test
  void aBlockedJobDoesNotStopAnotherJobFromRunning() {
    Jobs.reset();
    runner
        .withUserConfiguration(Jobs.class)
        .run(
            context -> {
              assertThat(Jobs.blockedStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
              try {
                // The first job is still blocked; the second one keeps running meanwhile.
                assertThat(Jobs.otherRuns.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                assertThat(Jobs.blockedRunning.get()).isEqualTo(1);
              } finally {
                Jobs.release.countDown();
              }
              assertThat(Jobs.threads).allMatch(name -> name.startsWith("scheduling-"));
              // Fixed delay: the blocked job never ran twice at once.
              assertThat(Jobs.maxConcurrentBlocked.get()).isEqualTo(1);
            });
  }

  @Test
  void aPoolSizeBelowOneStopsTheStart() {
    runner
        .withPropertyValues("app.scheduling.pool-size=0")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessage("app.scheduling.pool-size must be at least 1"));
  }

  @Test
  void invalidSettingsAreRefused() {
    assertThatThrownBy(() -> new SchedulingProperties(0, Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("app.scheduling.pool-size must be at least 1");
    assertThatThrownBy(() -> new SchedulingProperties(2, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("app.scheduling.await-termination must not be negative");
    assertThatThrownBy(() -> new SchedulingProperties(2, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new SchedulingProperties(1, Duration.ZERO).poolSize()).isEqualTo(1);
  }

  /** Scheduling on, with no job: Boot would create its own scheduler if ours were missing. */
  @Configuration
  @EnableScheduling
  static class SchedulingOn {}

  /** Two fixed-delay jobs, as every job of the application is. */
  @Configuration
  @EnableScheduling
  static class Jobs {

    static volatile CountDownLatch blockedStarted;
    static volatile CountDownLatch release;
    static volatile CountDownLatch otherRuns;
    static final AtomicInteger blockedRunning = new AtomicInteger();
    static final AtomicInteger maxConcurrentBlocked = new AtomicInteger();
    static final List<String> threads = new CopyOnWriteArrayList<>();

    static {
      reset();
    }

    static void reset() {
      blockedStarted = new CountDownLatch(1);
      release = new CountDownLatch(1);
      otherRuns = new CountDownLatch(3);
      blockedRunning.set(0);
      maxConcurrentBlocked.set(0);
      threads.clear();
    }

    @Scheduled(fixedDelay = 10)
    void blocked() throws InterruptedException {
      int running = blockedRunning.incrementAndGet();
      maxConcurrentBlocked.accumulateAndGet(running, Math::max);
      threads.add(Thread.currentThread().getName());
      try {
        blockedStarted.countDown();
        // Longer than the test waits for the other job, so one thread could never pass.
        release.await(3 * WAIT_SECONDS, TimeUnit.SECONDS);
      } finally {
        blockedRunning.decrementAndGet();
      }
    }

    @Scheduled(fixedDelay = 10)
    void other() {
      threads.add(Thread.currentThread().getName());
      otherRuns.countDown();
    }
  }
}
