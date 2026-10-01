package com.smartup24.cms.instance.warehouse.api;

import java.util.Map;

/**
 * A file row as it was read: its address in the source (sheet and row number) plus untyped fields. The core does
 * not know which fields a row has; that is instance data.
 *
 * @param rowNo       row number within the load (the write order)
 * @param sheet       source sheet, if the source had one
 * @param sourceRowNo row number in the file itself
 * @param fields      the row's fields as they are
 */
public record RawRow(long rowNo, String sheet, Integer sourceRowNo, Map<String, Object> fields) {}
