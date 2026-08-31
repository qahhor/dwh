package com.greenwhite.dwh.instance.analytics.dto;

public record TrendDataPointDto(
        String date,
        long createdCount,
        long completedCount
) {}
