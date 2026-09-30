package com.smartup24.cms.instance.common.retention;

import java.util.regex.Pattern;

/**
 * How long the rows of a journal table live (plan 10/10, item 3.13). A module declares its journals as beans; the
 * retention job deletes the rows the {@code expired} predicate selects for a cutoff {@code :cutoff} of now minus the
 * configured days ({@code smc.retention.days.<name>}, default {@code defaultDays}; 0 keeps the rows forever).
 *
 * @param name        the key of the setting and of the metric tag, e.g. {@code security-events}
 * @param table       the table, without schema
 * @param expired     the SQL predicate of a row past the cutoff, e.g. {@code created_at < :cutoff}
 * @param defaultDays the days a row lives when the setting is absent
 */
public record RetentionPolicy(String name, String table, String expired, int defaultDays) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern TABLE = Pattern.compile("[a-z][a-z0-9_]*");

    public RetentionPolicy {
        if (!NAME.matcher(name).matches() || !TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("Retention policy " + name + ": invalid name or table " + table);
        }
        if (!expired.contains(":cutoff")) {
            throw new IllegalArgumentException("Retention policy " + name + ": the predicate must use :cutoff");
        }
        if (defaultDays < 0) {
            throw new IllegalArgumentException("Retention policy " + name + ": negative default days");
        }
    }
}
