package com.smartup24.cms.instance.kauth.api;

import jakarta.validation.constraints.NotBlank;

public record ConfirmChannelRequest(
        @NotBlank String verifyToken, @NotBlank String code) {}
