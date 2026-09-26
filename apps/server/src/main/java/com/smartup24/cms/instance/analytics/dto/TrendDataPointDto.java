package com.smartup24.cms.instance.analytics.dto;

public record TrendDataPointDto(
        String date,
        long createdCount,
        long completedCount
) {}
