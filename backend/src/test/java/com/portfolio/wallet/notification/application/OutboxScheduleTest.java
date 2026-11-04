package com.portfolio.wallet.notification.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class OutboxScheduleTest {

  @Test
  void eachRunDispatchesTheDueRows() {
    OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);

    new OutboxSchedule(dispatcher).run();

    verify(dispatcher).dispatchDue();
  }
}
