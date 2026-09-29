package com.smartup24.cms.instance.ms.notify.api;

/** One setting of the notification settings screen. */
public record NotificationPrefUpdate(String eventType, String channel, boolean isEnabled) {}
