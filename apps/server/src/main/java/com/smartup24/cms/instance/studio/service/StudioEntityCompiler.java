package com.smartup24.cms.instance.studio.service;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.*;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.common.entity.EntityLogic;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Numbering;
import com.smartup24.cms.instance.md.api.MdSimpleFormDocumentDtos.Workflow;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Menu;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Option;
import com.smartup24.cms.instance.md.api.MdSimpleFormRuleDtos;
import com.smartup24.cms.instance.md.service.MdSimpleFormDocumentLogic;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityFields.Builder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>Format 6 (ADR-0043): a document has its number, its status and the comment of its last transition as system
 * fields, its tables of lines as collections and its statuses as a process ({@link StudioDocumentCompiler}); its saves
 * run the hooks of its number, comments and notices ({@link StudioDocumentHooks}).
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

    private final @Nullable StudioDocuments documents;

    /** A compiler without the services of documents: their numbers, comments and notices are not served. */
    public StudioEntityCompiler() {
        this((StudioDocuments) null);
    }

    public StudioEntityCompiler(@Nullable StudioDocuments documents) {
        this.documents = documents;
    }

    @Autowired
    public StudioEntityCompiler(ObjectProvider<StudioDocuments> documents) {
        this(documents.getIfAvailable());
    }

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
                .rights("studio", prefix + ".title", StudioDocumentCompiler.rights(source, prefix, RIGHTS));
        List<String> system = system(builder, source, title, prefix);
        List<String> fields = new ArrayList<>();
        EntityLogic logic = StudioFormLogic.of(source, prefix);
        for (Field field : StudioFormLayout.ordered(shown(source), title)) {
            Builder compiled = StudioFields.field(field, prefix, field.id().equals(title), named);
            // A computed field is written by the server on every save, never by a client (ADR-0042).
            if (field.formula() != null) compiled.readonly();
            builder.field(compiled);
            fields.add(field.id());
        }
        logic.rules().forEach(rule -> builder.rule("rule." + rule.key(), new StudioFormLogic.Check(rule)));
        if (!StudioFormLayout.NAME.equals(title)) builder.field(StudioFields.modifiedAt());
        List<EntityCollection> collections = StudioDocumentCompiler.collections(source, prefix, named);
        collections.forEach(builder::collection);
        StudioFormLayout.declare(builder, source, prefix, system, fields, last(builder, source), collections);
        Workflow workflow = source.workflow();
        if (workflow != null) {
            builder.workflow(StudioDocumentCompiler.workflow(
                    workflow,
                    prefix,
                    StudioDocumentCompiler.lockable(shown(source), source.lines()),
                    MdSimpleFormDocumentLogic.header(
                            source.fields(),
                            source.lines(),
                            workflow,
                            source.entity().numbering())));
        }
        builder.defaultSort("modifiedAt", Entity.Sort.DESC)
                .capabilities(
                        EntityCapability.SAVED_VIEWS,
                        EntityCapability.EXPORT,
                        EntityCapability.HISTORY,
                        EntityCapability.BULK);
        actions(builder, source, prefix, archived);
        var entity = builder.build();
        return new EntityDefinitionSource.Declaration(
                entity,
                messages(source, prefix),
                title,
                logic,
                hooks(id, source, title, archived),
                StudioDocumentCompiler.view(source, prefix));
    }

    /**
     * The menu item and the write actions of a served form; an archived form keeps neither — archived records stay apart
     * in the list, and the archive action itself is refused like every write.
     */
    private static void actions(Entity builder, Declaration source, String prefix, boolean archived) {
        if (archived) {
            builder.archivable();
            return;
        }
        Menu menu =
                source.entity().menu() == null ? DEFAULT_MENU : source.entity().menu();
        builder.menu(new EntityMenu(prefix + ".title", menu.icon(), menu.section(), menu.order(), "studio"))
                .actions("create", "update")
                .archivable()
                .actions("delete");
    }

    /**
     * The system fields that open the first section: the name and code or the record's number, then a document's
     * number and status (ADR-0043).
     */
    private static List<String> system(Entity builder, Declaration source, String title, String prefix) {
        List<String> system = new ArrayList<>();
        if (StudioFormLayout.NAME.equals(title)) {
            builder.field(StudioFields.name()).field(StudioFields.code()).field(StudioFields.modifiedAt());
            system.addAll(List.of("name", "code"));
        } else if (StudioFormLayout.RECORD_NUMBER.equals(title)) {
            builder.field(StudioFields.recordNumber());
            system.add(StudioFormLayout.RECORD_NUMBER);
        }
        Numbering numbering = source.entity().numbering();
        if (numbering != null) {
            builder.field(StudioDocumentCompiler.numberField(numbering, prefix));
            system.add(MdSimpleFormDocumentLogic.NUMBER);
        }
        Workflow workflow = source.workflow();
        if (workflow != null) {
            builder.field(StudioDocumentCompiler.status(workflow, prefix));
            system.add(MdSimpleFormDocumentLogic.STATUS);
        }
        return system;
    }

    /** The system fields that close the first section: a document's last comment, then the record's org unit. */
    private static List<String> last(Entity builder, Declaration source) {
        List<String> last = new ArrayList<>();
        if (source.workflow() != null) {
            builder.field(StudioDocumentCompiler.comment());
            last.add(MdSimpleFormDocumentLogic.COMMENT);
        }
        if ("org_unit".equals(source.entity().scope())) {
            builder.field(StudioFields.orgUnit());
            last.add(ORG_UNIT);
        }
        return last;
    }

    /**
     * The hooks of a document's saves: its number, the comments of its transitions and the notices of its steps; none
     * for a form that is no document, an archived one (read-only) or a compiler without the services of documents.
     */
    private @Nullable StudioDocumentHooks hooks(long id, Declaration source, String title, boolean archived) {
        StudioDocuments services = documents;
        if (services == null || archived || (source.entity().numbering() == null && source.workflow() == null)) {
            return null;
        }
        String scope = source.entity().scope() == null ? "all" : source.entity().scope();
        return new StudioDocumentHooks(
                new StudioDocumentHooks.Spec(
                        "studio." + source.entity().name(),
                        id,
                        scope,
                        title,
                        source.entity().title(),
                        source.entity().numbering(),
                        source.workflow()),
                services);
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
     * code names the form. A document keeps the right of each transition (ADR-0043): its statuses and transitions are
     * kept without what reads the fields — locks and conditions.
     */
    public EntityDefinitionSource.Declaration rights(
            long id, String name, @Nullable Labels title, @Nullable Workflow workflow) {
        Labels named = title == null || title.ru() == null || title.uz() == null || title.en() == null
                ? new Labels("studio." + name, "studio." + name, "studio." + name)
                : title;
        Workflow process = workflow == null ? null : StudioDocumentCompiler.rightsOnly(workflow);
        return compile(
                id,
                new Declaration(
                        process == null ? 1 : 6,
                        "simple-form",
                        new MdSimpleFormDtos.Module(StudioDefinitions.MODULE, named),
                        new MdSimpleFormDtos.Entity(name, named),
                        List.of(),
                        null,
                        null,
                        null,
                        process));
    }

    /** The rows a viewer sees, by the scope the form names; a form of format 1 or 2 sees all of them. */
    static EntityScope scope(@Nullable String scope) {
        return switch (scope == null ? "all" : scope) {
            case "owner" -> EntityScope.owner("created_by");
            case "org_unit" -> EntityScope.orgUnit("org_unit_id", "created_by");
            default -> EntityScope.all();
        };
    }

    private Map<String, Map<String, String>> messages(Declaration source, String prefix) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String language : List.of("ru", "uz", "en")) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(prefix + ".title", label(source.entity().title(), language));
            StudioFormLayout.messages(source, prefix, (key, labels) -> values.put(key, label(labels, language)));
            StudioDocumentCompiler.messages(source, prefix, (key, labels) -> values.put(key, label(labels, language)));
            if (source.rules() != null) {
                source.rules()
                        .forEach(rule -> values.put(
                                StudioFormLogic.messageKey(prefix, rule.key()), message(rule.message(), language)));
            }
            for (Field field : shown(source)) {
                values.put(prefix + ".field." + field.id(), label(field.labels(), language));
                if (field.options() == null) continue;
                for (Option option : field.options()) {
                    values.put(
                            StudioFields.optionPrefix(prefix, field.id()) + option.code(),
                            label(option.labels(), language));
                }
            }
            result.put(language, values);
        }
        return result;
    }

    private static String message(MdSimpleFormRuleDtos.RuleMessage message, String language) {
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
