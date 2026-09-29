package com.smartup24.cms.instance.ms.task.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AttachFileRequest(@NotNull UUID fileId) {}
