package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which changes a new version of a live form may make without moving data (ADR-0041, decision 2). A field keeps its key
 * and type for good — hiding it keeps its data; a field may be added when it is optional or has a default; labels,
 * order, the title and the menu item change freely; a field may become optional, take a longer text or more decimals.
 * Everything else — a removed or renamed key, another type, a required field without a default, a shorter text, fewer
 * decimals, a default added, changed or dropped on an existing field (records saved without the key would read
 * another value) — is refused, each change named by its field and code. The web editor shows the same rules.
 */
public final class StudioChangeRules {
    /** The message of every refused change; its parameters name the field and the change. */
    public static final String INCOMPATIBLE = "error.studio.change_incompatible";

    /** A change the rules refuse, by field id and change code. */
    public record Change(String field, String change) {}

    private StudioChangeRules() {}

    /** The refused changes from {@code active} to {@code next}, in the order of the fields; empty when compatible. */
    public static List<Change> incompatible(Declaration active, Declaration next) {
        Map<String, Field> following = next.fields().stream()
                .collect(Collectors.toMap(Field::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Change> changes = new ArrayList<>();
        for (Field before : active.fields()) {
            Field after = following.remove(before.id());
            if (after == null) {
                changes.add(new Change(before.id(), "removed"));
                continue;
            }
            field(before, after, changes);
        }
        for (Field added : following.values()) {
            if (added.required() && added.defaultValue() == null) changes.add(new Change(added.id(), "required"));
        }
        return changes;
    }

    private static void field(Field before, Field after, List<Change> changes) {
        String id = before.id();
        if (!before.type().equals(after.type())) {
            changes.add(new Change(id, "type"));
            return;
        }
        if (!Objects.equals(before.defaultValue(), after.defaultValue())) changes.add(new Change(id, "default"));
        if (!before.required() && after.required() && after.defaultValue() == null)
            changes.add(new Change(id, "required"));
        if (shorter(before.maxLength(), after.maxLength())) changes.add(new Change(id, "max_length"));
        if (shorter(before.scale(), after.scale())) changes.add(new Change(id, "scale"));
    }

    private static boolean shorter(Integer before, Integer after) {
        return before != null && after != null && after < before;
    }

    /** 422 naming every refused change: {@code error.studio.change_incompatible} with {@code field} and {@code change}. */
    public static ApiException refusal(List<Change> changes) {
        Change first = changes.getFirst();
        List<FieldErrorItem> errors = new ArrayList<>();
        for (Change change : changes) {
            Map<String, String> params = Map.of("field", change.field(), "change", change.change());
            errors.add(FieldErrorItem.keyed(
                    "fields." + change.field(), "change_" + change.change(), INCOMPATIBLE, params));
        }
        return ApiException.validation(INCOMPATIBLE, Map.of("field", first.field(), "change", first.change()), errors);
    }

    /**
     * The defaults of the served fields that the compiled form would refuse, checked with the rules every save applies,
     * so a default never makes every new record fail (ADR-0041): 422 naming each field.
     */
    public static void requireDefaults(Declaration declaration, EntityDefinition compiled) {
        List<FieldErrorItem> problems = new ArrayList<>();
        List<Field> fields = declaration.fields();
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            if (field.defaultValue() == null || Boolean.TRUE.equals(field.hidden())) continue;
            Object value = "bool".equals(field.type()) ? Boolean.valueOf(field.defaultValue()) : field.defaultValue();
            if (EntityValidator.refuses(compiled, field.id(), value)) {
                problems.add(FieldErrorItem.keyed(
                        "fields[" + index + "].defaultValue", "simple_form_default", "error.md.simple_form_default"));
            }
        }
        if (!problems.isEmpty()) throw ApiException.validation("error.md.simple_form_invalid", problems);
    }
}
