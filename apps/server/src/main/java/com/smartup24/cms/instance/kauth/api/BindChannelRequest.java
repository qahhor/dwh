package com.smartup24.cms.instance.kauth.api;

import jakarta.validation.constraints.NotBlank;

public record BindChannelRequest(
        @NotBlank String channel, @NotBlank String address) {}
