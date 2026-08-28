package com.greenwhite.dwh.instance.ms.task.event;

import java.util.List;

/**
 * Доменные события задачника (FR-TASK-8, ADR-0006 разд. 2.3 правило 3).
 * Модуль `tasks` НЕ вызывает `notify` напрямую — он объявляет, что произошло;
 * кто и как на это реагирует, задача не знает.
 *
 * Все события несут получателей списком: решение «кому слать» принимает
 * задачник (он знает роли участников), а не подписчик.
 */
public final class MsTaskEvents {

    private MsTaskEvents() {}

    /** Пользователь назначен на задачу (ответственным или исполнителем). */
    public record TaskAssigned(
            Long taskId,
            String taskTitle,
            List<Long> recipientUserIds,
            Long actorUserId
    ) {}

    /** Изменён статус задачи. */
    public record TaskStatusChanged(
            Long taskId,
            String taskTitle,
            String newStatusName,
            boolean terminal,
            List<Long> recipientUserIds,
            Long actorUserId
    ) {}

    /** Добавлен комментарий к задаче. */
    public record TaskCommented(
            Long taskId,
            String taskTitle,
            List<Long> recipientUserIds,
            Long actorUserId
    ) {}
}
