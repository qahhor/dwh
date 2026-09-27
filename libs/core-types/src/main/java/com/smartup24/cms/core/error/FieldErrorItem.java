package com.smartup24.cms.core.error;

/**
 * Field-level validation error detail.
 */
public record FieldErrorItem(
        String field,
        String code,
        String message
) {}
