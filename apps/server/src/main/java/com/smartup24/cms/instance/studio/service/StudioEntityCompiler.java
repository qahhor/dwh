package com.smartup24.cms.instance.studio.service;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.*;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.EntityLogic;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Menu;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Target;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.EntityFields.Builder;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Compiles the bounded declaration into the existing runtime, with a server-owned partition (ADR-0038). A hidden field
 * is left out — its data stays in the payload, which every save merges — and a fixed default is read for records saved
 * before the field was added (ADR-0041). An archived form compiles read-only: no menu item, no create, update or
 * delete; its archive action stays only so archived records keep apart in the list, and the write lock refuses it.
 *
 * <p>Format 3 (ADR-0041): a select keeps the code of one of its choices, named by the form's own labels; a reference to
 * a list is an enumeration of that list ({@link StudioListEnums}); a reference to a live form and a user field keep the
 * key of the row they name, which the runtime checks against the target's rights and scope. The data scope of the form
 * restricts every read and write of its records: all, the viewer's org units (the record keeps its author's home unit)
 * or the viewer's own.
 *
 * <p>Format 4 (ADR-0041): a form is named by its title field or by the record's number instead of the system name and
 * code, and lays its fields out in sections and tabs ({@link StudioFormLayout}).
 */
@Component
public class StudioEntityCompiler {
    /** The menu item of a form that does not name its own. */
    public static final Menu DEFAULT_MENU = new Menu("box", "workspace", 100);

    /** The record actions of every live form and the labels of their rights. */
    private static final Map<String, String> RIGHTS = Map.of(
            "view", "studio.record.view",
            "create", "studio.record.create",
            "update", "studio.record.update",
            "delete", "studio.record.delete");

    /** The entity a user field names. */
    public static final String USERS = "md.users";

    /** The key of the org unit of a record, served when the form's scope is the org unit. */
    public static final String ORG_UNIT = "orgUnit";

    /** The units a record may be moved to: those of the mover's data scope. */
    public static final String ORG_UNITS = "/iam/org-units/scope";

    public EntityDefinitionSource.Declaration compile(long id, Declaration source) {
        return compile(id, source, false);
    }

    public EntityDefinitionSource.Declaration compile(long id, Declaration source, boolean archived) {
        return compile(id, source, archived, target -> StudioFormLayout.NAME);
    }

    /**
     * The runtime declaration of a stored form; {@code titles} gives, for the code of a live form a reference targets,
     * the key of the field that names its records ({@link #titleKey}).
     */
    public EntityDefinitionSource.Declaration compile(
            long id, Declaration source, boolean archived, Function<String, String> titles) {
        String code = "studio." + source.entity().name();
        String prefix = "studio.live." + source.entity().name();
        String title = titleKey(source);
        Function<String, String> named = target -> target.equals(code) ? title : titles.apply(target);
        Entity builder = Entity.define(code, code)
                .table("studio_records", "t")
                .partition("form_id", id)
                .scope(scope(source.entity().scope()))
                .auditTable("studio_" + source.entity().name())
                .rights("studio", prefix + ".title", RIGHTS);
        List<String> system = new ArrayList<>();
        if (StudioFormLayout.NAME.equals(title)) {
            builder.field(name()).field(code()).field(modifiedAt());
            system.addAll(List.of("name", "code"));
        } else if (StudioFormLayout.RECORD_NUMBER.equals(title)) {
            builder.field(recordNumber());
            system.add(StudioFormLayout.RECORD_NUMBER);
        }
        List<String> fields = new ArrayList<>();
        EntityLogic logic = StudioFormLogic.of(source, prefix);
        for (Field field : StudioFormLayout.ordered(shown(source), title)) {
            Builder compiled = field(field, prefix, field.id().equals(title), named);
            // A computed field is written by the server on every save, never by a client (ADR-0042).
            if (field.formula() != null) compiled.readonly();
            builder.field(compiled);
            fields.add(field.id());
        }
        logic.rules().forEach(rule -> builder.rule("rule." + rule.key(), new StudioFormLogic.Check(rule)));
        if (!StudioFormLayout.NAME.equals(title)) builder.field(modifiedAt());
        String last = null;
        if ("org_unit".equals(source.entity().scope())) {
            builder.field(orgUnit());
            last = ORG_UNIT;
        }
        StudioFormLayout.declare(builder, source, prefix, system, fields, last);
        builder.defaultSort("modifiedAt", Entity.Sort.DESC)
                .capabilities(
                        EntityCapability.SAVED_VIEWS,
                        EntityCapability.EXPORT,
                        EntityCapability.HISTORY,
                        EntityCapability.BULK);
        if (archived) {
            // Archived records stay apart in the list; the archive action itself is refused like every write.
            builder.archivable();
        } else {
            Menu menu = source.entity().menu() == null
                    ? DEFAULT_MENU
                    : source.entity().menu();
            builder.menu(new EntityMenu(prefix + ".title", menu.icon(), menu.section(), menu.order(), "studio"))
                    .actions("create", "update")
                    .archivable()
                    .actions("delete");
        }
        return new EntityDefinitionSource.Declaration(builder.build(), messages(source, prefix), title, logic);
    }

    /**
     * The key of the field that names a record of the form - on its card, in references to it and in reports: the
     * system {@code name} for formats 1 to 3 and a form of format 4 that keeps it, else the form's title field, else the
     * record's number.
     */
    public static String titleKey(Declaration source) {
        return StudioFormLayout.titleKey(source);
    }

    /** The fields a form serves: every field that is not hidden, in declaration order. */
    public static List<Field> shown(Declaration source) {
        return source.fields().stream()
                .filter(field -> !Boolean.TRUE.equals(field.hidden()))
                .toList();
    }

    /**
     * The form of a stored declaration that is not served — it no longer compiles, it clashes or it is held — reduced
     * to what its rights need: the entity, its actions and its title. The fields add no right, so the permission pairs
     * are the ones {@link #compile} registers for the same code (ADR-0038, decision 10). Without a readable title the
     * code names the form.
     */
    public EntityDefinitionSource.Declaration rights(long id, String name, @Nullable Labels title) {
        Labels named = title == null || title.ru() == null || title.uz() == null || title.en() == null
                ? new Labels("studio." + name, "studio." + name, "studio." + name)
                : title;
        return compile(
                id,
                new Declaration(
                        1,
                        "simple-form",
                        new MdSimpleFormDtos.Module(StudioDefinitions.MODULE, named),
                        new MdSimpleFormDtos.Entity(name, named),
                        List.of()));
    }

    /**
     * The record's org unit: its author's home unit by default (ADR-0041, owner decision 1). A user with the update right
     * moves the record to another unit; the runtime keeps both the old and the new unit within the mover's scope, and
     * the picker offers the units of that scope.
     */
    private static Builder orgUnit() {
        return ref(ORG_UNIT, "studio.record.org_unit", QueryRef.whole(ORG_UNITS, "name"))
                .column("org_unit_id")
                .defaultValue(FieldDefault.currentOrgUnit())
                .list(hidden());
    }

    private static Builder name() {
        return text("name", "studio.record.name")
                .column("name")
                .required()
                .length(1, 255)
                .list(sortable().searchable());
    }

    private static Builder code() {
        return text("code", "studio.record.code")
                .column("code")
                .required()
                .length(1, 64)
                .matching("[a-z0-9_-]+")
                .list(sortable().searchable());
    }

    private static Builder modifiedAt() {
        return instant("modifiedAt", "studio.record.modified_at")
                .system(SystemColumn.MODIFIED_AT)
                .list(sortable());
    }

    /**
     * The record's number in its form, given by the database in turn (V224) and only read; a record saved before V224
     * has none, so the number is listed but not sorted.
     */
    private static Builder recordNumber() {
        return number(StudioFormLayout.RECORD_NUMBER, "studio.record.number")
                .column("record_number")
                .readonly();
    }

    /** The rows a viewer sees, by the scope the form names; a form of format 1 or 2 sees all of them. */
    static EntityScope scope(@Nullable String scope) {
        return switch (scope == null ? "all" : scope) {
            case "owner" -> EntityScope.owner("created_by");
            case "org_unit" -> EntityScope.orgUnit("org_unit_id", "created_by");
            default -> EntityScope.all();
        };
    }

    private Builder field(Field field, String prefix, boolean title, Function<String, String> titles) {
        String key = field.id();
        String label = prefix + ".field." + key;
        Builder builder = switch (field.type()) {
            case "text" -> text(key, label);
            case "textarea" -> textarea(key, label);
            case "number" -> number(key, label);
            case "date" -> date(key, label);
            case "datetime" -> instant(key, label);
            case "time" -> time(key, label);
            case "bool" -> bool(key, label);
            case "email" -> email(key, label);
            case "phone" -> phone(key, label);
            case "url" -> url(key, label);
            case "select" -> select(key, label, codes(field), optionPrefix(prefix, key));
            case "user" -> ref(key, label).target(USERS, "name");
            case "ref" -> reference(key, label, field, titles);
            default -> throw new IllegalArgumentException("Unsupported live field type");
        };
        builder.jsonProperty("payload", key);
        if (field.required()) builder.required();
        if (field.maxLength() != null) builder.length(null, field.maxLength());
        if (field.scale() != null) builder.scale(field.scale());
        if (!field.listed()) builder.formOnly();
        else if (title) builder.list(searchable());
        if (field.defaultValue() != null) builder.defaultValue(FieldDefault.fixed(field.defaultValue()));
        return builder;
    }

    /** A reference to a list is an enumeration of it; one to a live form keeps the key of its record. */
    private static Builder reference(String key, String label, Field field, Function<String, String> titles) {
        Target target = Objects.requireNonNull(field.target(), "A live reference names its target");
        return "list".equals(target.kind())
                ? enumeration(key, label, StudioListEnums.reference(target.code()))
                : ref(key, label).target(target.code(), titles.apply(target.code()));
    }

    private static List<String> codes(Field field) {
        return Objects.requireNonNull(field.options(), "A live select has its choices").stream()
                .map(Option::code)
                .toList();
    }

    private static String optionPrefix(String prefix, String key) {
        return prefix + ".option." + key + ".";
    }

    private Map<String, Map<String, String>> messages(Declaration source, String prefix) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String language : List.of("ru", "uz", "en")) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(prefix + ".title", label(source.entity().title(), language));
            StudioFormLayout.messages(source, prefix, (key, labels) -> values.put(key, label(labels, language)));
            if (source.rules() != null) {
                source.rules()
                        .forEach(rule -> values.put(
                                StudioFormLogic.messageKey(prefix, rule.key()), message(rule.message(), language)));
            }
            for (Field field : shown(source)) {
                values.put(prefix + ".field." + field.id(), label(field.labels(), language));
                if (field.options() == null) continue;
                for (Option option : field.options()) {
                    values.put(optionPrefix(prefix, field.id()) + option.code(), label(option.labels(), language));
                }
            }
            result.put(language, values);
        }
        return result;
    }

    private static String message(MdSimpleFormDtos.RuleMessage message, String language) {
        return switch (language) {
            case "uz" -> message.uz();
            case "en" -> message.en();
            default -> message.ru();
        };
    }

    private String label(Labels labels, String language) {
        return switch (language) {
            case "uz" -> labels.uz();
            case "en" -> labels.en();
            default -> labels.ru();
        };
    }
}
