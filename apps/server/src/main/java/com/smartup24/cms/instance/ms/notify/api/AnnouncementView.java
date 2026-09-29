package com.smartup24.cms.instance.ms.notify.api;

import java.time.Instant;

/** A published announcement the reader has not read yet, in the reader's language. */
public record AnnouncementView(Long id, String title, String body, String bannerType, Instant publishedAt) {}
