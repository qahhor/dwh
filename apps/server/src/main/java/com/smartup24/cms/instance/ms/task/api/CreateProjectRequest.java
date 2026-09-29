package com.smartup24.cms.instance.ms.task.api;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record CreateProjectRequest(
        @NotBlank String name, String description, String state, Map<String, Object> attributes) {}
