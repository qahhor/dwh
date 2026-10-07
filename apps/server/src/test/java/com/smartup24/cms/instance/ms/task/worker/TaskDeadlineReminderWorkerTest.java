package com.smartup24.cms.instance.ms.task.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class TaskDeadlineReminderWorkerTest {

    private final MsTaskStatsRepository taskRepository = Mockito.mock(MsTaskStatsRepository.class);
    private final MsNotificationService notificationService = Mockito.mock(MsNotificationService.class);
    private final MdUserTexts texts = Mockito.mock(MdUserTexts.class);
    private final TaskDeadlineReminderWorker worker =
            new TaskDeadlineReminderWorker(taskRepository, notificationService, texts);

    @BeforeEach
    void setUp() {
        when(notificationService.isNotificationEnabled(anyLong(), anyString(), anyString()))
                .thenReturn(true);
        // The recipient's language: the catalog text of each key, its placeholders filled.
        when(texts.text(anyLong(), anyString(), Mockito.<Map<String, String>>any()))
                .thenAnswer(call -> "[" + call.getArgument(0) + "] " + call.getArgument(1) + " "
                        + new TreeMap<>(call.<Map<String, String>>getArgument(2)));
    }

    @Test
    @DisplayName("Отправляет уведомление о дедлайне, если оно не отправлялось ранее")
    void sendsDeadlineNotificationWhenNotSentRecently() {
        var candidate = new MsTaskStatsRepository.TaskDeadlineCandidate(101L, "Сдать финансовый отчёт", 10L);
        when(taskRepository.findUpcomingDeadlines(any(Duration.class))).thenReturn(List.of(candidate));
        when(notificationService.hasRecentNotification(eq(10L), eq("task_deadline_101"), any(Duration.class)))
                .thenReturn(false);

        worker.scanAndNotifyDeadlines();

        // Catalog keys in the recipient's language (ru, uz, en), never a text of the code.
        verify(notificationService)
                .sendInAppNotification(
                        eq(10L),
                        eq(TaskDeadlineReminderWorker.REMINDER_TYPE),
                        eq("[10] notify.task_deadline.title {hours=24, id=101, title=Сдать финансовый отчёт}"),
                        eq("[10] notify.task_deadline.body {hours=24, id=101, title=Сдать финансовый отчёт}"),
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

    @Test
    @DisplayName("The reminder's strings exist in ru, uz and en with their placeholders")
    void theStringsAreInEveryCatalog() throws Exception {
        for (String language : List.of("ru", "uz", "en")) {
            try (var in = getClass().getResourceAsStream("/i18n/" + language + ".json")) {
                Map<?, ?> catalog = new ObjectMapper().readValue(in, Map.class);
                assertThat((String) catalog.get(TaskDeadlineReminderWorker.TITLE))
                        .as(language)
                        .contains("{id}");
                assertThat((String) catalog.get(TaskDeadlineReminderWorker.BODY))
                        .as(language)
                        .contains("{title}", "{hours}");
            }
        }
    }
}
