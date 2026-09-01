package com.greenwhite.dwh.cp.deployment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AssignTargetCommand(
        @NotNull UUID releaseId,
        @NotBlank @Size(max = 64) String configVersion,
        @NotNull RolloutRing ring,
        @Valid @NotNull MaintenanceWindow maintenanceWindow) {
}
