package com.smartup24.cms.instance.ms.task.api;

import jakarta.validation.constraints.NotBlank;

public record CreateTypeRequest(
        @NotBlank String code, @NotBlank String name, String icon, String color, int orderNo) {}
