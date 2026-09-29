package com.smartup24.cms.instance.ms.notify.api;

/** Whether an event reaches the user through a channel. */
public record NotificationPrefView(Long userId, String eventType, String channel, boolean isEnabled) {}
