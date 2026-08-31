package com.greenwhite.dwh.instance.ms.task.worker;

import com.greenwhite.dwh.instance.ms.notify.service.MsNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskDeadlineReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskDeadlineReminderWorker.class);

    private final JdbcClient jdbcClient;
    private final MsNotificationService notificationService;

    public TaskDeadlineReminderWorker(JdbcClient jdbcClient, MsNotificationService notificationService) {
        this.jdbcClient = jdbcClient;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelay = 600000, initialDelay = 30000)
    public void scanAndNotifyDeadlines() {
        try {
            List<TaskDeadlineRow> rows = jdbcClient.sql("""
                    select distinct t.id as task_id, t.title, tm.user_id
                    from ms_tasks t
                    join ms_task_statuses s on s.id = t.status_id and s.is_terminal = false
                    join ms_task_members tm on tm.task_id = t.id
                    where t.end_time is not null
                      and t.end_time > now()
                      and t.end_time <= now() + interval '24 hours'
                    """)
                    .query((rs, rowNum) -> new TaskDeadlineRow(
                            rs.getLong("task_id"),
                            rs.getString("title"),
                            rs.getLong("user_id")
                    ))
                    .list();

            if (rows.isEmpty()) {
                return;
            }

            for (var row : rows) {
                // Avoid flooding: check if notification was already sent in last 24h
                boolean alreadySent = jdbcClient.sql("""
                        select exists(
                            select 1 from ms_notifications
                            where user_id = :userId
                              and source_code = :sourceCode
                              and created_at >= now() - interval '24 hours'
                        )
                        """)
                        .param("userId", row.userId())
                        .param("sourceCode", "task_deadline_" + row.taskId())
                        .query(Boolean.class)
                        .single();

                if (!alreadySent) {
                    notificationService.sendInAppNotification(
                            row.userId(),
                            "deadline_warning",
                            "Приближается дедлайн по задаче #" + row.taskId(),
                            "Срок выполнения задачи '" + row.title() + "' истекает в ближайшие 24 часа.",
                            "/tasks",
                            "task_deadline_" + row.taskId()
                    );
                    log.info("Deadline notification sent for task #{} to user #{}", row.taskId(), row.userId());
                }
            }
        } catch (Exception e) {
            log.warn("TaskDeadlineReminderWorker error: {}", e.getMessage());
        }
    }

    private record TaskDeadlineRow(long taskId, String title, long userId) {}
}
