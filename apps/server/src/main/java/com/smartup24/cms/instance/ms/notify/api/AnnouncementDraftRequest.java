package com.smartup24.cms.instance.ms.notify.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;

public record AnnouncementDraftRequest(
        @NotNull Map<String, String> titleJson,
        @NotNull Map<String, String> bodyJson,
        @NotBlank String bannerType,
        @PositiveOrZero Long lockVersion) {}
