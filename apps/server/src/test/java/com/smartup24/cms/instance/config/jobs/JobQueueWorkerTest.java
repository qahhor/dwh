package com.smartup24.cms.instance.config.jobs;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.fnd.jobs.FndJobRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Запускатель очереди: порядок вызовов и живучесть при сбое задания. */
class JobQueueWorkerTest {

    @Test
    @DisplayName("такт сначала ставит задания расписания, затем выполняет очередь")
    void tickEnqueuesThenRuns() {
        FndJobRunner runner = mock(FndJobRunner.class);

        new JobQueueWorker(runner).tick();

        InOrder order = inOrder(runner);
        order.verify(runner).enqueueDue();
        order.verify(runner).runQueued();
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("сбой внутри такта не выпускает исключение наружу")
    void tickSwallowsFailure() {
        FndJobRunner runner = mock(FndJobRunner.class);
        doThrow(new IllegalStateException("TEST сбой очереди")).when(runner).enqueueDue();

        assertThatCode(() -> new JobQueueWorker(runner).tick()).doesNotThrowAnyException();
    }
}
