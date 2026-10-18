package com.smartup24.cms.instance.md.service;

import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.bool;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.integer;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.invalid;
import static com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations.known;

import com.smartup24.cms.instance.md.api.MdSimpleFormFileDtos;
import com.smartup24.cms.instance.md.api.MdSimpleFormFileDtos.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The file field format 7 of a simple form adds (ADR-0043): one file or several up to {@code maxFiles}, the content
 * types it takes and its largest size. A file field has no fixed default and no formula of its value — a record gets
 * its files from a person, through the files module — and no column of a table of lines: a row has no files.
 */
final class MdSimpleFormFiles {
    static final String TYPE = "file";

    /** Content types of one field. */
    static final int MAX_TYPES = 20;

    /** Format 7 adds the rules of a file field. */
    static final Set<String> FIELD_V7 = union(MdSimpleFormDeclarations.FIELD_V5, "files");

    private static final String CONTENT_TYPE = "[a-z]+/[a-z0-9][a-z0-9.+-]{0,99}";
    private static final Set<String> KEYS = Set.of("multiple", "maxFiles", "contentTypes", "maxSizeMb");

    private MdSimpleFormFiles() {}

    /** The rules of a file field: null for another type, and for a file field that takes one file of any type. */
    static @Nullable Files files(JsonNode field, String type, String at) {
        if (!TYPE.equals(type)) {
            if (field.has("files")) throw invalid(at + ".files", "unsupported");
            return null;
        }
        for (String key : List.of("defaultValue", "formula", "defaultFormula")) {
            if (field.has(key)) throw invalid(at + "." + key, "unsupported");
        }
        if (!field.has("files")) return null;
        JsonNode files = field.get("files");
        String path = at + ".files";
        known(files, KEYS, path);
        boolean multiple = bool(files, "multiple", path + ".multiple");
        if (!multiple && files.has("maxFiles")) throw invalid(path + ".maxFiles", "unsupported");
        Integer most =
                multiple ? integer(files, "maxFiles", path + ".maxFiles", 2, MdSimpleFormFileDtos.MAX_FILES) : null;
        List<String> types = files.has("contentTypes") ? contentTypes(files.get("contentTypes"), path) : null;
        Integer size = files.has("maxSizeMb")
                ? integer(files, "maxSizeMb", path + ".maxSizeMb", 1, MdSimpleFormFileDtos.MAX_SIZE_MB)
                : null;
        return new Files(multiple, most, types, size);
    }

    private static Set<String> union(Set<String> keys, String key) {
        Set<String> all = new HashSet<>(keys);
        all.add(key);
        return Set.copyOf(all);
    }

    private static List<String> contentTypes(JsonNode types, String at) {
        String path = at + ".contentTypes";
        if (!types.isArray()) throw invalid(path, "structure");
        if (types.isEmpty() || types.size() > MAX_TYPES) throw invalid(path, "limit");
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < types.size(); index++) {
            JsonNode type = types.get(index);
            String item = path + "[" + index + "]";
            if (!type.isString()) throw invalid(item, "structure");
            String value = type.asString();
            if (!value.matches(CONTENT_TYPE)) throw invalid(item, "identifier");
            if (!seen.add(value)) throw invalid(item, "duplicate");
            result.add(value);
        }
        return List.copyOf(result);
    }
}
