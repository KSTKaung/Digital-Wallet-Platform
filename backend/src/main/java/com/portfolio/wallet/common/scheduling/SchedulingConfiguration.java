package com.portfolio.wallet.common.scheduling;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The one scheduler of every {@code @Scheduled} job (owner decision on the PR 5a step 6 security
 * review, L2).
 *
 * <p>With {@code spring.threads.virtual.enabled: true}, Boot's own scheduler is a {@code
 * SimpleAsyncTaskScheduler}: it ignores {@code spring.task.scheduling.pool.size} and runs every
 * fixed-delay task on one thread, so a long transfer-expiry run would hold back the email outbox
 * and the purges. This bean replaces it with a {@link ThreadPoolTaskScheduler} of {@link
 * SchedulingProperties#poolSize()} platform threads named {@code scheduling-}:
 *
 * <ul>
 *   <li>Boot's scheduler backs off: its configuration applies only when no {@code TaskScheduler}
 *       bean exists;
 *   <li>{@code @Scheduled} processing uses this bean, both through its name {@code taskScheduler}
 *       and explicitly through {@link SchedulingConfigurer}, so no other scheduler can be picked;
 *   <li>fixed delay is kept: the next run of a job is scheduled only after its previous run ended,
 *       so a job never overlaps itself; different jobs run side by side, up to the pool size;
 *   <li>shutdown waits for running jobs, at most {@link SchedulingProperties#awaitTermination()},
 *       as Boot's {@code spring.task.scheduling.shutdown} does. A run still going after that is cut
 *       when the context closes; each job works in transactions of its own (one transfer each for
 *       the expiry), so the one in progress rolls back whole and a later run redoes it.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(SchedulingProperties.class)
class SchedulingConfiguration implements SchedulingConfigurer {

  static final String THREAD_NAME_PREFIX = "scheduling-";

  private final SchedulingProperties properties;

  SchedulingConfiguration(SchedulingProperties properties) {
    this.properties = properties;
  }

  @Bean(name = "taskScheduler")
  ThreadPoolTaskScheduler taskScheduler() {
    return newScheduler(properties);
  }

  /** Never a second scheduler: {@code @Scheduled} jobs are registered on the bean above. */
  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.setTaskScheduler(taskScheduler());
  }

  static ThreadPoolTaskScheduler newScheduler(SchedulingProperties properties) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(properties.poolSize());
    scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationMillis(properties.awaitTermination().toMillis());
    return scheduler;
  }
}
