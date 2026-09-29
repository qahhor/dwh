package com.smartup24.cms.instance.ms.task.api;

import java.time.Instant;

/** A task type of the dictionary. */
public record TaskTypeView(
        Long id,
        String code,
        String name,
        String icon,
        String color,
        int orderNo,
        boolean isSystem,
        Instant createdAt) {}
