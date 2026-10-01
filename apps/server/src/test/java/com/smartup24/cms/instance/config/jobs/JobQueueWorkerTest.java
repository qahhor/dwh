package com.smartup24.cms.instance.config.jobs;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** The queue runner: the order of calls and survival when a job fails. */
class JobQueueWorkerTest {

    @Test
    @DisplayName("такт сначала ставит задания расписания, затем выполняет очередь")
    void tickEnqueuesThenRuns() {
        JobRunner runner = mock(JobRunner.class);

        new JobQueueWorker(runner).tick();

        InOrder order = inOrder(runner);
        order.verify(runner).enqueueDue();
        order.verify(runner).runQueued();
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("сбой внутри такта не выпускает исключение наружу")
    void tickSwallowsFailure() {
        JobRunner runner = mock(JobRunner.class);
        doThrow(new IllegalStateException("TEST сбой очереди")).when(runner).enqueueDue();

        assertThatCode(() -> new JobQueueWorker(runner).tick()).doesNotThrowAnyException();
    }
}
