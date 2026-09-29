package com.smartup24.cms.instance.ms.task.worker;

import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskDeadlineReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskDeadlineReminderWorker.class);
    private static final Duration DEADLINE_WINDOW = Duration.ofHours(24);

    private final MsTaskStatsRepository taskRepository;
    private final MsNotificationService notificationService;

    public TaskDeadlineReminderWorker(MsTaskStatsRepository taskRepository, MsNotificationService notificationService) {
        this.taskRepository = taskRepository;
        this.notificationService = notificationService;
    }

    /**
     * Notification type of a reminder. It must be one of the types ms_notifications allows (info, success,
     * warning, danger): the former "deadline_warning" broke the check constraint, so no reminder was ever saved.
     */
    static final String REMINDER_TYPE = "warning";

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
                remind(row);
            } catch (RuntimeException e) {
                log.warn("deadline_reminder_failed task={} user={}", row.taskId(), row.userId(), e);
            }
        }
    }

    private void remind(MsTaskStatsRepository.TaskDeadlineCandidate row) {
        if (!notificationService.isNotificationEnabled(row.userId(), "task_deadline_reminder", "in_app")) {
            return;
        }
        String reminderKey = "task_deadline_" + row.taskId();
        // One reminder per task and person within the window.
        if (notificationService.hasRecentNotification(row.userId(), reminderKey, DEADLINE_WINDOW)) {
            return;
        }
        notificationService.sendInAppNotification(
                row.userId(),
                REMINDER_TYPE,
                "Приближается дедлайн по задаче #" + row.taskId(),
                "Срок выполнения задачи '" + row.title() + "' истекает в ближайшие 24 часа.",
                "/tasks",
                reminderKey);
        log.info("deadline_reminder_sent task={} user={}", row.taskId(), row.userId());
    }
}
