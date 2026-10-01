package com.smartup24.cms.instance.ms.notify.sse;

import com.smartup24.cms.instance.ms.notify.repository.MsNotificationRepository;

/**
 * Domain event: an in-app notification was created for a user.
 * Published inside the transaction and delivered to subscribers AFTER commit;
 * otherwise a client that got the push could read data that is not committed yet.
 */
public record MsNotificationCreatedEvent(Long userId, MsNotificationRepository.NotificationRecord notification) {}
