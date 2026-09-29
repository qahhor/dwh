package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;

/** Typed Typesense field configuration shared by query construction and saved policy. */
public record FieldPolicy(String field, int weight, int numTypos, boolean prefix) {

    // A settings request builds this record: a broken rule is the administrator's 400, not a 500.
    public FieldPolicy {
        if (field == null || field.isBlank())
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.field_required");
        if (weight < 0 || weight > 127)
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.field_weight_range");
        if (numTypos < 0 || numTypos > 2)
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.search.field_typos_range");
    }
}
