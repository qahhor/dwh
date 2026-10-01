package com.smartup24.cms.instance.config.env;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The configuration names used before ADR-0027 and their replacements (plan 10/10, item 4.1).
 *
 * <p>The product settings moved from {@code dwh.*} / {@code DWH_*} to {@code smc.*} / {@code SMC_*}; the settings of
 * the warehouse, the second database pg-dwh, moved from {@code app.dwh.*} / {@code APP_DWH_*} to
 * {@code warehouse.*} / {@code WAREHOUSE_*}. An old name keeps working until {@link #SUNSET}, the end of the
 * transition shared with ADR-0023, and each old name in use is reported once at startup.
 */
public final class LegacyConfigNames {

    /** The last day an old name is read. */
    public static final LocalDate SUNSET = LocalDate.of(2026, 12, 31);

    /** Environment names whose replacement does not follow the prefix rules; checked first. */
    private static final Map<String, String> ENVIRONMENT_EXCEPTIONS = Map.of(
            "DWH_DB_URL", "DB_URL",
            "DWH_DB_USER", "DB_USER",
            "DWH_DB_PASSWORD", "DB_PASSWORD",
            "DWH_DATA_DB_URL", "WAREHOUSE_URL",
            "DWH_DATA_DB_USER", "WAREHOUSE_USERNAME",
            "DWH_DATA_DB_PASSWORD", "WAREHOUSE_PASSWORD",
            "DWH_DB_NAME", "WAREHOUSE_DB_NAME");

    /** Prefix rules of environment names; the first match wins, so the longer prefixes come first. */
    private static final List<Map.Entry<String, String>> ENVIRONMENT_PREFIXES = List.of(
            Map.entry("APP_DWH_", "WAREHOUSE_"), Map.entry("DWH_FND_JOBS_", "SMC_JOBS_"), Map.entry("DWH_", "SMC_"));

    /** Prefix rules of property keys; the first match wins. */
    private static final List<Map.Entry<String, String>> PROPERTY_PREFIXES = List.of(
            Map.entry("app.dwh.", "warehouse."), Map.entry("dwh.fnd.jobs.", "smc.jobs."), Map.entry("dwh.", "smc."));

    private LegacyConfigNames() {}

    /** The current name of an old environment variable, or empty when the name is not an old one. */
    public static Optional<String> environmentVariable(String name) {
        String exception = ENVIRONMENT_EXCEPTIONS.get(name);
        if (exception != null) {
            return Optional.of(exception);
        }
        return renamed(name, ENVIRONMENT_PREFIXES);
    }

    /** The current key of an old property, or empty when the key is not an old one. */
    public static Optional<String> property(String key) {
        return renamed(key, PROPERTY_PREFIXES);
    }

    /** The startup warning about one old name; it never carries the value. */
    public static String warning(String oldName, String newName) {
        return "Configuration name " + oldName + " is deprecated (ADR-0027): use " + newName
                + "; the old name is read only until " + SUNSET;
    }

    private static Optional<String> renamed(String name, List<Map.Entry<String, String>> rules) {
        for (Map.Entry<String, String> rule : rules) {
            if (name.startsWith(rule.getKey()) && name.length() > rule.getKey().length()) {
                return Optional.of(
                        rule.getValue() + name.substring(rule.getKey().length()));
            }
        }
        return Optional.empty();
    }
}
