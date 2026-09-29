package com.smartup24.cms.instance.ms.task.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TaskDeadlineReminderWorkerTest {

    private final MsTaskStatsRepository taskRepository = Mockito.mock(MsTaskStatsRepository.class);
    private final MsNotificationService notificationService = Mockito.mock(MsNotificationService.class);
    private final TaskDeadlineReminderWorker worker =
            new TaskDeadlineReminderWorker(taskRepository, notificationService);

    @BeforeEach
    void setUp() {
        when(notificationService.isNotificationEnabled(anyLong(), anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("Отправляет уведомление о дедлайне, если оно не отправлялось ранее")
    void sendsDeadlineNotificationWhenNotSentRecently() {
        var candidate = new MsTaskStatsRepository.TaskDeadlineCandidate(101L, "Сдать финансовый отчёт", 10L);
        when(taskRepository.findUpcomingDeadlines(any(Duration.class))).thenReturn(List.of(candidate));
        when(notificationService.hasRecentNotification(eq(10L), eq("task_deadline_101"), any(Duration.class)))
                .thenReturn(false);

        worker.scanAndNotifyDeadlines();

        verify(notificationService)
                .sendInAppNotification(
                        eq(10L),
                        eq(TaskDeadlineReminderWorker.REMINDER_TYPE),
                        eq("Приближается дедлайн по задаче #101"),
                        eq("Срок выполнения задачи 'Сдать финансовый отчёт' истекает в ближайшие 24 часа."),
                        eq("/tasks"),
                        eq("task_deadline_101"));
    }

    @Test
    @DisplayName("Не отправляет повторное уведомление, если оно уже отправлялось недавно")
    void suppressesNotificationWhenAlreadySentRecently() {
        var candidate = new MsTaskStatsRepository.TaskDeadlineCandidate(102L, "Обновить сертификаты", 20L);
        when(taskRepository.findUpcomingDeadlines(any(Duration.class))).thenReturn(List.of(candidate));
        when(notificationService.hasRecentNotification(eq(20L), eq("task_deadline_102"), any(Duration.class)))
                .thenReturn(true);

        worker.scanAndNotifyDeadlines();

        verify(notificationService, never())
                .sendInAppNotification(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Не отправляет уведомление, если отключено в настройках пользователя")
    void suppressesNotificationWhenDisabledInPreferences() {
        var candidate = new MsTaskStatsRepository.TaskDeadlineCandidate(103L, "Провести аудит", 30L);
        when(taskRepository.findUpcomingDeadlines(any(Duration.class))).thenReturn(List.of(candidate));
        when(notificationService.isNotificationEnabled(eq(30L), eq("task_deadline_reminder"), eq("in_app")))
                .thenReturn(false);

        worker.scanAndNotifyDeadlines();

        verify(notificationService, never())
                .sendInAppNotification(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Корректно обрабатывает пустой список приближающихся дедлайнов")
    void handlesEmptyDeadlinesGracefully() {
        when(taskRepository.findUpcomingDeadlines(any(Duration.class))).thenReturn(List.of());

        worker.scanAndNotifyDeadlines();

        verify(notificationService, never())
                .sendInAppNotification(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Сбой на одной задаче не останавливает напоминания по остальным")
    void failureOnOneTaskDoesNotStopTheScan() {
        var broken = new MsTaskStatsRepository.TaskDeadlineCandidate(104L, "Сломанная", 40L);
        var fine = new MsTaskStatsRepository.TaskDeadlineCandidate(105L, "Рабочая", 50L);
        when(taskRepository.findUpcomingDeadlines(any(Duration.class))).thenReturn(List.of(broken, fine));
        when(notificationService.hasRecentNotification(eq(40L), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("db down"));

        worker.scanAndNotifyDeadlines();

        verify(notificationService)
                .sendInAppNotification(
                        eq(50L),
                        eq(TaskDeadlineReminderWorker.REMINDER_TYPE),
                        anyString(),
                        anyString(),
                        eq("/tasks"),
                        eq("task_deadline_105"));
    }
}
