package com.smartup24.cms.instance.ms.notify.api;

import com.smartup24.cms.instance.ms.notify.model.AnnouncementState;
import java.time.Instant;
import java.util.Map;

/** An announcement as its editors manage it: every language and the lifecycle state. */
public record ManagedAnnouncementView(
        Long id,
        Map<String, String> titleJson,
        Map<String, String> bodyJson,
        String bannerType,
        AnnouncementState state,
        Long createdBy,
        Instant createdAt,
        Instant modifiedAt,
        Instant publishedAt,
        Instant archivedAt,
        long lockVersion) {}
