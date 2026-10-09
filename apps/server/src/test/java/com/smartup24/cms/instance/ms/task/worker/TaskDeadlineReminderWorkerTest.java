package com.smartup24.cms.instance.ms.task.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.ms.task.api.MsTaskEvents;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** FR-TASK-8: the scan announces each person of a task due soon, each in a transaction of its own. */
class TaskDeadlineReminderWorkerTest {

    private final MsTaskStatsRepository taskRepository = mock(MsTaskStatsRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final List<Object> published = new ArrayList<>();
    private final List<Boolean> inTransaction = new ArrayList<>();
    private boolean transactionOpen;
    private final TaskDeadlineReminderWorker worker = new TaskDeadlineReminderWorker(
            taskRepository,
            event -> {
                published.add(event);
                inTransaction.add(transactionOpen);
            },
            transactions);

    @BeforeEach
    void transactionsOpenAndCommit() {
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            transactionOpen = true;
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> {
                    transactionOpen = false;
                    return null;
                })
                .when(transactions)
                .commit(any());
    }

    @Test
    @DisplayName("FR-TASK-8: every person of a task due within a day is announced in a transaction of its own")
    void announcesEachCandidate() {
        when(taskRepository.findUpcomingDeadlines(any(Duration.class)))
                .thenReturn(List.of(
                        new MsTaskStatsRepository.TaskDeadlineCandidate(101L, "Report", 10L),
                        new MsTaskStatsRepository.TaskDeadlineCandidate(101L, "Report", 20L)));

        worker.scanAndNotifyDeadlines();

        assertThat(published)
                .containsExactly(
                        new MsTaskEvents.TaskDeadlineApproaching(
                                101L, "Report", 10L, TaskDeadlineReminderWorker.DEADLINE_WINDOW),
                        new MsTaskEvents.TaskDeadlineApproaching(
                                101L, "Report", 20L, TaskDeadlineReminderWorker.DEADLINE_WINDOW));
        assertThat(inTransaction).containsExactly(true, true);
        verify(transactions, times(2)).commit(any());
    }

    @Test
    @DisplayName("FR-TASK-8: no task due soon announces nothing")
    void handlesEmptyDeadlinesGracefully() {
        when(taskRepository.findUpcomingDeadlines(any(Duration.class))).thenReturn(List.of());

        worker.scanAndNotifyDeadlines();

        assertThat(published).isEmpty();
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    @DisplayName("FR-TASK-8: a failure on one person does not stop the scan")
    void failureOnOneTaskDoesNotStopTheScan() {
        when(taskRepository.findUpcomingDeadlines(any(Duration.class)))
                .thenReturn(List.of(
                        new MsTaskStatsRepository.TaskDeadlineCandidate(104L, "Broken", 40L),
                        new MsTaskStatsRepository.TaskDeadlineCandidate(105L, "Fine", 50L)));
        doThrow(new IllegalStateException("db down"))
                .doAnswer(call -> {
                    transactionOpen = false;
                    return null;
                })
                .when(transactions)
                .commit(any());

        worker.scanAndNotifyDeadlines();

        assertThat(published).hasSize(2);
        assertThat(((MsTaskEvents.TaskDeadlineApproaching) published.get(1)).recipientUserId())
                .isEqualTo(50L);
    }

    @Test
    @DisplayName("FR-TASK-8: a failing scan is logged and sends nothing")
    void failingScanSendsNothing() {
        when(taskRepository.findUpcomingDeadlines(any(Duration.class))).thenThrow(new IllegalStateException("down"));

        worker.scanAndNotifyDeadlines();

        assertThat(published).isEmpty();
    }
}
