package com.smartup24.cms.instance.ms.task.api;

import java.time.Instant;
import java.util.Map;

/** A project as the API answers it. */
public record ProjectView(
        Long id,
        String name,
        String description,
        String state,
        Map<String, Object> attributes,
        Instant createdAt,
        Long createdBy) {}
