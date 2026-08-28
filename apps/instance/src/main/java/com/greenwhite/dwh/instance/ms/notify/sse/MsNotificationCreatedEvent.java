package com.greenwhite.dwh.instance.ms.notify.sse;

import com.greenwhite.dwh.instance.ms.notify.repository.MsNotificationRepository;

/**
 * Доменное событие: пользователю создано in-app уведомление.
 * Публикуется внутри транзакции, доставляется подписчикам ПОСЛЕ коммита —
 * иначе клиент, получив push, мог бы прочитать ещё не зафиксированные данные.
 */
public record MsNotificationCreatedEvent(
        Long userId,
        MsNotificationRepository.NotificationRecord notification
) {}
