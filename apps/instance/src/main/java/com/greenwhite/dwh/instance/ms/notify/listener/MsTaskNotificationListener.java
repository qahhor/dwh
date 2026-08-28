package com.greenwhite.dwh.instance.ms.notify.listener;

import com.greenwhite.dwh.instance.ms.notify.pref.MsNotifyPref;
import com.greenwhite.dwh.instance.ms.notify.service.MsNotificationService;
import com.greenwhite.dwh.instance.ms.task.event.MsTaskEvents;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Превращает доменные события задачника в in-app уведомления (FR-TASK-8).
 *
 * Слушатель обычный (не AFTER_COMMIT): уведомление должно попасть в БД
 * в ТОЙ ЖЕ транзакции, что и само изменение задачи — иначе возможна задача
 * без уведомления при откате или наоборот. Доставка в SSE уже отложена
 * до коммита внутри MsNotificationService (MsSsePublisher).
 *
 * Автор действия исключается из получателей: не уведомляем человека
 * о том, что он сам только что сделал.
 */
@Component
@Profile("!migrate")
public class MsTaskNotificationListener {

    private static final String LINK_TASK = "/tasks/items/";

    private final MsNotificationService notificationService;

    public MsTaskNotificationListener(MsNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @EventListener
    public void onTaskAssigned(MsTaskEvents.TaskAssigned event) {
        notifyAll(event.recipientUserIds(), event.actorUserId(),
                MsNotifyPref.TYPE_INFO,
                "Вам назначена задача",
                event.taskTitle(),
                event.taskId(),
                "task-assigned-" + event.taskId());
    }

    @EventListener
    public void onTaskStatusChanged(MsTaskEvents.TaskStatusChanged event) {
        notifyAll(event.recipientUserIds(), event.actorUserId(),
                event.terminal() ? MsNotifyPref.TYPE_SUCCESS : MsNotifyPref.TYPE_INFO,
                "Статус задачи: " + event.newStatusName(),
                event.taskTitle(),
                event.taskId(),
                // source_code с учётом статуса: смена на новый статус — новое
                // уведомление, повторная установка того же — обновление прежнего
                "task-status-" + event.taskId() + "-" + event.newStatusName());
    }

    @EventListener
    public void onTaskCommented(MsTaskEvents.TaskCommented event) {
        notifyAll(event.recipientUserIds(), event.actorUserId(),
                MsNotifyPref.TYPE_INFO,
                "Новый комментарий к задаче",
                event.taskTitle(),
                event.taskId(),
                null); // комментарии не схлопываем: важен каждый
    }

    private void notifyAll(List<Long> recipients, Long actorUserId, String type,
                           String title, String body, Long taskId, String sourceCode) {
        if (recipients == null) {
            return;
        }
        for (Long userId : recipients) {
            if (userId == null || userId.equals(actorUserId)) {
                continue;
            }
            notificationService.sendInAppNotification(
                    userId, type, title, body, LINK_TASK + taskId, sourceCode);
        }
    }
}
