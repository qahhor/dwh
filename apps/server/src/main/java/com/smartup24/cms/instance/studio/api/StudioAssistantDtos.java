package com.smartup24.cms.instance.studio.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Assistant responses carry a declaration, never a prepared package (ADR-0039). */
public final class StudioAssistantDtos {
    private StudioAssistantDtos() {}

    @Schema(name = "StudioAssistantAvailability", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Availability(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean available) {}

    @Schema(name = "StudioAssistantRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Request(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 2000)
            String prompt) {}
}
