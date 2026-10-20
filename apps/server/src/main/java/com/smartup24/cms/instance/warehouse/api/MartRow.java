package com.smartup24.cms.instance.warehouse.api;

import java.util.Map;
import java.util.Objects;

/**
 * One normalized row of an applied warehouse load for {@code mart.rows} in {@code pg-dwh} (ADR-0048).
 *
 * @param rowNo  row number within the load
 * @param sheet  sheet name in the uploaded file
 * @param fields normalized values keyed by {@code target_field}: numbers in base units, dates in ISO-8601, padded keys
 */
public record MartRow(long rowNo, String sheet, Map<String, Object> fields) {

    public MartRow {
        Objects.requireNonNull(sheet, "sheet");
        Objects.requireNonNull(fields, "fields");
    }
}
