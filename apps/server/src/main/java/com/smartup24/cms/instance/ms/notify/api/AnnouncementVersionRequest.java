package com.smartup24.cms.instance.ms.notify.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** The version the editor saw: publishing or archiving a changed announcement is refused. */
public record AnnouncementVersionRequest(
        @NotNull @PositiveOrZero Long lockVersion) {}
