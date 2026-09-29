package com.smartup24.cms.instance.ms.task.api;

import jakarta.validation.constraints.NotBlank;

public record CreateStatusRequest(String pcode, @NotBlank String name, String color, int orderNo, boolean isTerminal) {}
