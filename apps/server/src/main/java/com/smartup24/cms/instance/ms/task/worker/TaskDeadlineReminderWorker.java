package com.smartup24.cms.instance.ms.task.worker;

import com.smartup24.cms.instance.ms.task.api.MsTaskEvents;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds the people of a task whose deadline comes within a day and announces each of them as
 * {@link MsTaskEvents.TaskDeadlineApproaching} (FR-TASK-8). The task module does not notify anyone itself: the
 * notification module listens, writes the reminder in the person's language and sends it once per task and person.
 *
 * <p>Every node of a cluster runs the scan. Each announcement runs in a transaction of its own, and the subscriber
 * decides and records the reminder inside it under a lock on the person and the task, so two nodes scanning together
 * still send one reminder.
 */
@Component
@Profile("!migrate")
public class TaskDeadlineReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskDeadlineReminderWorker.class);

    /** How far ahead a deadline is reminded of. */
    static final Duration DEADLINE_WINDOW = Duration.ofHours(24);

    private final MsTaskStatsRepository taskRepository;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    public TaskDeadlineReminderWorker(
            MsTaskStatsRepository taskRepository,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A failure on one task is logged and the scan goes on with the next one. */
    @Scheduled(fixedDelay = 600000, initialDelay = 30000)
    public void scanAndNotifyDeadlines() {
        List<MsTaskStatsRepository.TaskDeadlineCandidate> rows;
        try {
            rows = taskRepository.findUpcomingDeadlines(DEADLINE_WINDOW);
        } catch (RuntimeException e) {
            log.warn("deadline_scan_failed", e);
            return;
        }
        for (var row : rows) {
            try {
                transactions.executeWithoutResult(
                        status -> events.publishEvent(new MsTaskEvents.TaskDeadlineApproaching(
                                row.taskId(), row.title(), row.userId(), DEADLINE_WINDOW)));
            } catch (RuntimeException e) {
                log.warn("deadline_reminder_failed task={} user={}", row.taskId(), row.userId(), e);
            }
        }
    }
}
