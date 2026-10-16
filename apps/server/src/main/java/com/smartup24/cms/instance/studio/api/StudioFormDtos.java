package com.smartup24.cms.instance.studio.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Live create-only activation responses (ADR-0038). */
public final class StudioFormDtos {
    private StudioFormDtos() {}

    @Schema(name = "StudioAvailability")
    public record Availability(boolean enabled, String namespace, int maxForms) {}

    @Schema(name = "StudioActivatedForm")
    public record Activated(String entityCode, String checksum) {}
}
