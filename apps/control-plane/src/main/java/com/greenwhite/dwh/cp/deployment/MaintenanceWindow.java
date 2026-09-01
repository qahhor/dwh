package com.greenwhite.dwh.cp.deployment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record MaintenanceWindow(
        @Min(1) @Max(5) int weekOfMonth,
        @Min(1) @Max(7) int dayOfWeek,
        @NotNull LocalTime start,
        @Min(15) @Max(240) int durationMinutes,
        @NotBlank @Size(max = 64) String timezone) {
}
