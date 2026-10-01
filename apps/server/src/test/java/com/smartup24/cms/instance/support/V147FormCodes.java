package com.smartup24.cms.instance.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * The form codes V147 moved (ADR-0028, plan 10/10, item 4.4), read from the released migration itself: the server no
 * longer translates old codes, so the frozen file is the only record of the mapping.
 */
public final class V147FormCodes {

    /** The released migration that renamed the forms. */
    public static final String MIGRATION = "db/migration/V147__permission_codes_by_module.sql";

    /** One row of the mapping array: {@code ['<old>', '<new>', '<module>']}. */
    private static final Pattern ROW = Pattern.compile("\\['([a-z_.]+)', '([a-z_.]+)', '([a-z_.]+)'\\]");

    /** One moved form: its old code, its new code and the module V147 recorded as the owner. */
    public record Row(String old, String current, String module) {}

    private V147FormCodes() {}

    /** The rows of the mapping in the order V147 lists them. */
    public static List<Row> rows() {
        String sql;
        try {
            sql = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Row> rows = new ArrayList<>();
        Matcher row = ROW.matcher(sql);
        while (row.find()) {
            rows.add(new Row(row.group(1), row.group(2), row.group(3)));
        }
        return List.copyOf(rows);
    }

    /** Old form code to its successor. */
    public static Map<String, String> successors() {
        Map<String, String> successors = new LinkedHashMap<>();
        rows().forEach(row -> successors.put(row.old(), row.current()));
        return successors;
    }

    /** A {@code form.action} key with an old form code replaced by its successor. */
    public static String currentPermission(String permission) {
        int dot = permission.lastIndexOf('.');
        if (dot <= 0) {
            return permission;
        }
        String current = successors().get(permission.substring(0, dot));
        return current == null ? permission : current + permission.substring(dot);
    }
}
