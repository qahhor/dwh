package com.smartup24.cms.instance.kauth.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record CreateTokenRequest(@NotBlank String name, Instant expiresAt) {}
