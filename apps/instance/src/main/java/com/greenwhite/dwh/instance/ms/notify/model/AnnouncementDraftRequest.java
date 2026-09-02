package com.greenwhite.dwh.instance.ms.notify.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Map;

public record AnnouncementDraftRequest(
        @NotNull Map<String, String> titleJson,
        @NotNull Map<String, String> bodyJson,
        @NotBlank String bannerType,
        @PositiveOrZero Long lockVersion
) {
}
