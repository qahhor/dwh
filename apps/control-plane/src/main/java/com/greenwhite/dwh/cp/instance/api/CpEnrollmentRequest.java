package com.greenwhite.dwh.cp.instance.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CpEnrollmentRequest(
        @NotBlank @Size(max = 128) String enrollmentToken) {
}
