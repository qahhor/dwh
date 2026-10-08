package com.smartup24.cms.instance.ms.notify.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdUserTexts;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.service.MsNotificationService;
import com.smartup24.cms.instance.ms.task.api.MsTaskEvents;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

/**
 * FR-TASK-8: task events become in-app notifications written in the recipient's language. The texts come from the
 * real ru, uz and en catalogs: user 10 reads Russian, 20 Uzbek, 30 English.
 */
class MsTaskNotificationListenerTest {

    private static final MdI18nCatalog CATALOG = new MdI18nCatalog(new ObjectMapper());
    private static final Map<Long, String> LANGUAGES = Map.of(10L, "ru", 20L, "uz", 30L, "en");

    private final MsNotificationService notificationService = Mockito.mock(MsNotificationService.class);
    private final MdUserTexts texts = Mockito.mock(MdUserTexts.class);
    private final MsTaskNotificationListener listener = new MsTaskNotificationListener(notificationService, texts);

    @BeforeEach
    void setUp() {
        when(notificationService.isNotificationEnabled(any(), any(), any())).thenReturn(true);
        when(texts.text(any(), anyString(), Mockito.<Map<String, String>>any()))
                .thenAnswer(call -> MdUserTexts.fill(
                        CATALOG.bundled(LANGUAGES.getOrDefault(call.<Long>getArgument(0), "ru"))
                                .getOrDefault(call.<String>getArgument(1), ""),
                        call.getArgument(2)));
    }

    private static String text(String language, String key) {
        return CATALOG.bundled(language).get(key);
    }

    @Test
    @DisplayName("FR-TASK-8: the responsible person reads the assignment in Russian")
    void notifiesResponsibleWithRoleText() {
        listener.onTaskAssigned(new MsTaskEvents.TaskAssigned(
                100L, "Подготовить отчёт", List.of(10L), MsTaskPref.INVOLVE_RESPONSIBLE, 1L));

        verify(notificationService)
                .sendInAppNotification(
                        eq(10L),
                        eq(MsNotifyPref.TYPE_INFO),
                        eq("Вы назначены ответственным за задачу"),
                        eq("Подготовить отчёт"),
                        eq("/tasks/items/100"),
                        eq("task-assigned-100-R"));
    }

    @Test
    @DisplayName("FR-TASK-8: a co-executor reads the assignment in Uzbek, an observer in English")
    void notifiesEachRecipientInTheirLanguage() {
        listener.onTaskAssigned(
                new MsTaskEvents.TaskAssigned(100L, "Report", List.of(20L), MsTaskPref.INVOLVE_EXECUTOR, 1L));
        listener.onTaskAssigned(
                new MsTaskEvents.TaskAssigned(100L, "Report", List.of(30L), MsTaskPref.INVOLVE_OBSERVER, 1L));

        verify(notificationService)
                .sendInAppNotification(
                        eq(20L),
                        eq(MsNotifyPref.TYPE_INFO),
                        eq(text("uz", "notify.task_assigned.executor")),
                        eq("Report"),
                        eq("/tasks/items/100"),
                        eq("task-assigned-100-E"));
        verify(notificationService)
                .sendInAppNotification(
                        eq(30L),
                        eq(MsNotifyPref.TYPE_INFO),
                        eq("You were added as an observer of the task"),
                        eq("Report"),
                        eq("/tasks/items/100"),
                        eq("task-assigned-100-O"));
    }

    @Test
    @DisplayName("FR-TASK-8: the author of the action is not notified of it")
    void excludesActorFromNotification() {
        listener.onTaskAssigned(new MsTaskEvents.TaskAssigned(
                100L, "Подготовить отчёт", List.of(1L, 10L), MsTaskPref.INVOLVE_EXECUTOR, 1L));

        verify(notificationService)
                .sendInAppNotification(
                        eq(10L),
                        eq(MsNotifyPref.TYPE_INFO),
                        eq("Вы добавлены соисполнителем задачи"),
                        eq("Подготовить отчёт"),
                        eq("/tasks/items/100"),
                        eq("task-assigned-100-E"));
        verify(notificationService, never()).sendInAppNotification(eq(1L), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("FR-TASK-8: a status change names the new status in the recipient's sentence")
    void notifiesOnStatusChanged() {
        listener.onTaskStatusChanged(
                new MsTaskEvents.TaskStatusChanged(100L, "Report", "Done", true, List.of(30L), 1L));

        verify(notificationService)
                .sendInAppNotification(
                        eq(30L),
                        eq(MsNotifyPref.TYPE_SUCCESS),
                        eq("Task status: Done"),
                        eq("Report"),
                        eq("/tasks/items/100"),
                        eq("task-status-100-Done"));
    }

    @Test
    @DisplayName("FR-TASK-8: a comment and a deadline change are announced to the members")
    void notifiesOnCommentAndDeadline() {
        listener.onTaskCommented(new MsTaskEvents.TaskCommented(100L, "Report", List.of(30L), 1L));
        listener.onTaskDeadlineChanged(new MsTaskEvents.TaskDeadlineChanged(
                100L, "Подготовить отчёт", Instant.now(), Instant.now().plusSeconds(86400), List.of(10L), 1L));

        verify(notificationService)
                .sendInAppNotification(
                        eq(30L),
                        eq(MsNotifyPref.TYPE_INFO),
                        eq("New comment on the task"),
                        eq("Report"),
                        eq("/tasks/items/100"),
                        eq(null));
        verify(notificationService)
                .sendInAppNotification(
                        eq(10L),
                        eq(MsNotifyPref.TYPE_WARNING),
                        eq("Изменён дедлайн задачи"),
                        eq("Подготовить отчёт"),
                        eq("/tasks/items/100"),
                        eq("task-deadline-100"));
    }

    @Test
    @DisplayName("FR-TASK-8: removal from a role names the role")
    void notifiesOnMemberRemovedWithRole() {
        listener.onTaskMemberRemoved(new MsTaskEvents.TaskMemberRemoved(
                100L, "Подготовить отчёт", List.of(10L), MsTaskPref.INVOLVE_EXECUTOR, 1L));

        verify(notificationService)
                .sendInAppNotification(
                        eq(10L),
                        eq(MsNotifyPref.TYPE_INFO),
                        eq("Вы сняты с роли соисполнителя задачи"),
                        eq("Подготовить отчёт"),
                        eq("/tasks/items/100"),
                        eq("task-removed-100"));
    }

    @Test
    @DisplayName("FR-TASK-8: a notification switched off in the preferences is not sent")
    void suppressesNotificationWhenDisabledInPreferences() {
        when(notificationService.isNotificationEnabled(eq(10L), eq("task_assigned"), eq("in_app")))
                .thenReturn(false);

        listener.onTaskAssigned(new MsTaskEvents.TaskAssigned(
                100L, "Подготовить отчёт", List.of(10L), MsTaskPref.INVOLVE_OBSERVER, 1L));

        verify(notificationService, never()).sendInAppNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("FR-TASK-8: an approaching deadline is reminded once, in the recipient's language")
    void remindsOfAnApproachingDeadlineOnce() {
        listener.onTaskDeadlineApproaching(
                new MsTaskEvents.TaskDeadlineApproaching(101L, "Annual report", 30L, Duration.ofHours(24)));

        verify(notificationService)
                .sendInAppNotificationOnce(
                        30L,
                        MsNotifyPref.TYPE_WARNING,
                        "Task #101 is due soon",
                        "The task \"Annual report\" is due within 24 h.",
                        "/tasks/items/101",
                        "task_deadline_101",
                        Duration.ofHours(24));
    }

    @Test
    @DisplayName("FR-TASK-8: a reminder switched off in the preferences is not sent")
    void reminderRespectsThePreferences() {
        when(notificationService.isNotificationEnabled(eq(30L), eq("task_deadline_reminder"), eq("in_app")))
                .thenReturn(false);

        listener.onTaskDeadlineApproaching(
                new MsTaskEvents.TaskDeadlineApproaching(101L, "Annual report", 30L, Duration.ofHours(24)));

        verify(notificationService, never())
                .sendInAppNotificationOnce(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("FR-TASK-8: every string of the task notifications exists in ru, uz and en with its placeholders")
    void everyStringIsInEveryCatalog() {
        for (String language : List.of("ru", "uz", "en")) {
            for (String role : MsTaskNotificationListener.ROLES) {
                assertThat(text(language, MsTaskNotificationListener.ASSIGNED + role))
                        .as(language + " " + role)
                        .isNotBlank();
                assertThat(text(language, MsTaskNotificationListener.REMOVED + role))
                        .as(language + " " + role)
                        .isNotBlank();
            }
            assertThat(text(language, MsTaskNotificationListener.STATUS)).contains("{status}");
            assertThat(text(language, MsTaskNotificationListener.COMMENT)).isNotBlank();
            assertThat(text(language, MsTaskNotificationListener.DEADLINE_CHANGED))
                    .isNotBlank();
            assertThat(text(language, MsTaskNotificationListener.DEADLINE_TITLE))
                    .contains("{id}");
            assertThat(text(language, MsTaskNotificationListener.DEADLINE_BODY)).contains("{title}", "{hours}");
        }
    }
}
