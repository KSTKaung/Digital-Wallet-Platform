package com.portfolio.wallet.common.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.auth.application.AuthPurgeSchedule;
import com.portfolio.wallet.notification.application.OutboxSchedule;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.transfer.application.TransferExpirySchedule;
import com.portfolio.wallet.transfer.application.TransferIdempotencyPurgeSchedule;
import com.portfolio.wallet.wallet.application.IdempotencyPurgeSchedule;
import com.portfolio.wallet.wallet.application.LookupPurgeSchedule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The full application context of the integration tests: the one scheduler is the configured pool
 * of 2, and every scheduled job is still turned off ({@code PostgresTestDatabase}), so no
 * background run races a test.
 */
class SchedulingIT extends DatabaseIntegrationTest {

  @Autowired ApplicationContext context;

  @Test
  void theApplicationsSchedulerIsThePoolOfTwo() {
    assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(1);
    ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
    assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
  }

  /**
   * The scheduler is an Executor too; {@code spring.task.execution.mode: force} keeps Boot's
   * virtual-thread {@code applicationTaskExecutor} (direct emails) as it was, apart from the pool.
   */
  @Test
  void theApplicationTaskExecutorIsStillBootsVirtualThreadExecutor() {
    Object executor = context.getBean("applicationTaskExecutor");
    assertThat(executor)
        .isInstanceOf(SimpleAsyncTaskExecutor.class)
        .isNotSameAs(context.getBean(ThreadPoolTaskScheduler.class));
  }

  @Test
  void everyScheduledJobIsOffInTheTests() {
    for (Class<?> schedule :
        List.of(
            OutboxSchedule.class,
            AuthPurgeSchedule.class,
            IdempotencyPurgeSchedule.class,
            LookupPurgeSchedule.class,
            TransferIdempotencyPurgeSchedule.class,
            TransferExpirySchedule.class)) {
      assertThat(context.getBeanNamesForType(schedule)).as(schedule.getSimpleName()).isEmpty();
    }
    assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values())
        .allSatisfy(holder -> assertThat(holder.getScheduledTasks()).isEmpty());
  }
}
