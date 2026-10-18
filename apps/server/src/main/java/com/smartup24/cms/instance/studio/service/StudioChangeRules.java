package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Which changes a new version of a live form may make without moving data (ADR-0041, decision 2). A field keeps its key
 * and type for good — hiding it keeps its data; a field may be added when it is optional or has a default; labels,
 * order, the title and the menu item change freely; a field may become optional, take a longer text or more decimals.
 * Everything else — a removed or renamed key, another type, a required field without a default, a shorter text, fewer
 * decimals, a default added, changed or dropped on an existing field (records saved without the key would read
 * another value) — is refused, each change named by its field and code. The web editor shows the same rules.
 *
 * <p>Format 3 (ADR-0041): a select may gain choices and rename their labels, but a choice whose code records may hold
 * is never dropped ({@code option_removed}); a reference keeps its target ({@code target}); the data scope of the form
 * stays the one chosen at creation ({@link #SCOPE}): a wider one would show records to viewers who never saw them, a
 * narrower one would hide records saved without an org unit.
 *
 * <p>Format 4 (ADR-0041): the layout and the title field change freely — they only show the same data otherwise —
 * but a form keeps or goes without the system name and code fields for good: dropping them would drop data the records
 * hold ({@code name}, {@code removed}), taking them on would require a name of every record ({@code required}).
 *
 * <p>Format 5 (ADR-0042): formulas only act when a record is saved — a read never evaluates one — so the logic changes
 * freely: a computed field's formula (its records take the new value on their next save), defaults by formula, the
 * conditions and the rules may be added, changed or removed, and a new computed field starts empty in the records saved
 * before it. A field whose records hold entered values never becomes computed ({@code computed}): the next save would
 * replace them; a computed field may become an entered one and keeps its values.
 *
 * <p>Format 6 (ADR-0043): the tables of lines, the number and the statuses follow {@link StudioDocumentChangeRules}.
 *
 * <p>Format 7 (ADR-0043): a file field is added like any optional field; a field never becomes or stops being a file
 * field ({@code type}), never switches between one file and several ({@code files_multiple}: records keep one id or an
 * array) and never holds fewer files than before ({@code max_files}); its content types and largest size change freely
 * — they limit only the files a later save attaches.
 */
public final class StudioChangeRules {
    /** The message of every refused change; its parameters name the field and the change. */
    public static final String INCOMPATIBLE = "error.studio.change_incompatible";

    /** The message of a changed data scope, addressed to {@code entity.scope}. */
    public static final String SCOPE = "error.studio.scope_change";

    /** The change of the data scope: no field has this id, field ids start with a letter and have no dot. */
    static final Change SCOPE_CHANGE = new Change("entity.scope", "scope");

    /** A change the rules refuse, by field id and change code. */
    public record Change(String field, String change) {}

    private StudioChangeRules() {}

    /** The refused changes from {@code active} to {@code next}, in the order of the fields; empty when compatible. */
    public static List<Change> incompatible(Declaration active, Declaration next) {
        Map<String, Field> following = next.fields().stream()
                .collect(Collectors.toMap(Field::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Change> changes = new ArrayList<>();
        if (!scope(active).equals(scope(next))) changes.add(SCOPE_CHANGE);
        boolean named = named(active);
        if (named && !named(next)) changes.add(new Change(StudioFormLayout.NAME, "removed"));
        if (!named && named(next)) changes.add(new Change(StudioFormLayout.NAME, "required"));
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
        StudioDocumentChangeRules.incompatible(active, next, changes);
        return changes;
    }

    private static void field(Field before, Field after, List<Change> changes) {
        String id = before.id();
        if (!before.type().equals(after.type())) {
            changes.add(new Change(id, "type"));
            return;
        }
        if (!Objects.equals(before.target(), after.target())) changes.add(new Change(id, "target"));
        if (dropsOption(before, after)) changes.add(new Change(id, "option_removed"));
        if (!Objects.equals(before.defaultValue(), after.defaultValue())) changes.add(new Change(id, "default"));
        if (!before.required() && after.required() && after.defaultValue() == null)
            changes.add(new Change(id, "required"));
        if (shorter(before.maxLength(), after.maxLength())) changes.add(new Change(id, "max_length"));
        if (shorter(before.scale(), after.scale())) changes.add(new Change(id, "scale"));
        if (before.formula() == null && after.formula() != null) changes.add(new Change(id, "computed"));
        if (multiple(before) != multiple(after)) changes.add(new Change(id, "files_multiple"));
        else if (shorter(maxFiles(before), maxFiles(after))) changes.add(new Change(id, "max_files"));
    }

    /** Whether a file field holds several files: its records keep an array of ids rather than one (ADR-0043). */
    private static boolean multiple(Field field) {
        return field.files() != null && field.files().multiple();
    }

    private static @Nullable Integer maxFiles(Field field) {
        return field.files() == null ? null : field.files().maxFiles();
    }

    /** Whether a choice of the select before is no longer one of the choices after: records may hold its code. */
    private static boolean dropsOption(Field before, Field after) {
        if (before.options() == null) return false;
        Set<String> kept = after.options() == null
                ? Set.of()
                : after.options().stream().map(Option::code).collect(Collectors.toSet());
        return before.options().stream().map(Option::code).anyMatch(code -> !kept.contains(code));
    }

    /** Whether the form keeps the system name and code fields: formats 1 to 3, or format 4 named by {@code name}. */
    private static boolean named(Declaration declaration) {
        return StudioFormLayout.NAME.equals(StudioFormLayout.titleKey(declaration));
    }

    private static String scope(Declaration declaration) {
        String scope = declaration.entity().scope();
        return scope == null ? "all" : scope;
    }

    private static boolean shorter(@Nullable Integer before, @Nullable Integer after) {
        return before != null && after != null && after < before;
    }

    /** 422 naming every refused change: {@code error.studio.change_incompatible} with {@code field} and {@code change}. */
    public static ApiException refusal(List<Change> changes) {
        Change first = changes.getFirst();
        List<FieldErrorItem> errors = new ArrayList<>();
        for (Change change : changes) {
            if (change.equals(SCOPE_CHANGE)) {
                errors.add(FieldErrorItem.keyed(change.field(), "change_scope", SCOPE));
                continue;
            }
            Map<String, String> params = Map.of("field", change.field(), "change", change.change());
            // A field id has no dot; a document's part is addressed by its own path (ADR-0043).
            String at = change.field().contains(".") || "workflow".equals(change.field())
                    ? change.field()
                    : "fields." + change.field();
            errors.add(FieldErrorItem.keyed(at, "change_" + change.change(), INCOMPATIBLE, params));
        }
        if (first.equals(SCOPE_CHANGE)) return ApiException.validation(SCOPE, errors);
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
