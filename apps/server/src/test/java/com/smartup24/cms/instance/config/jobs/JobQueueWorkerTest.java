package com.smartup24.cms.instance.config.jobs;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.smartup24.cms.instance.jobs.runner.JobRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** The queue runner: the order of calls, survival when a job fails, and no tick before the startup runners. */
class JobQueueWorkerTest {

    private static JobQueueWorker ready(JobRunner runner) {
        JobQueueWorker worker = new JobQueueWorker(runner);
        worker.onReady();
        return worker;
    }

    @Test
    @DisplayName("6.5: before the application is ready a tick runs no job (the bootstrap admin may not exist yet)")
    void noTickBeforeTheApplicationIsReady() {
        JobRunner runner = mock(JobRunner.class);
        JobQueueWorker worker = new JobQueueWorker(runner);

        worker.tick();
        verifyNoInteractions(runner);

        worker.onReady();
        worker.tick();
        InOrder order = inOrder(runner);
        order.verify(runner).enqueueDue();
        order.verify(runner).runQueued();
    }

    @Test
    @DisplayName("такт сначала ставит задания расписания, затем выполняет очередь")
    void tickEnqueuesThenRuns() {
        JobRunner runner = mock(JobRunner.class);

        ready(runner).tick();

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

        assertThatCode(() -> ready(runner).tick()).doesNotThrowAnyException();
    }
}
