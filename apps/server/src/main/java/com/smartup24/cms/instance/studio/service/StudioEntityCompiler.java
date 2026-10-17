package com.smartup24.cms.instance.studio.service;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.*;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.EntityFields.Builder;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Compiles the bounded declaration into the existing runtime, with a server-owned partition (ADR-0038). */
@Component
public class StudioEntityCompiler {
    public EntityDefinitionSource.Declaration compile(long id, Declaration source) {
        String code = "studio." + source.entity().name();
        String prefix = "studio.live." + source.entity().name();
        Entity builder = Entity.define(code, code)
                .table("studio_records", "t")
                .partition("form_id", id)
                .scope(EntityScope.all())
                .auditTable("studio_" + source.entity().name())
                .rights(
                        "studio",
                        prefix + ".title",
                        Map.of(
                                "view",
                                "studio.record.view",
                                "create",
                                "studio.record.create",
                                "update",
                                "studio.record.update",
                                "delete",
                                "studio.record.delete"))
                .menu(new EntityMenu(prefix + ".title", "box", "workspace", 100, "studio"))
                .field(text("name", "studio.record.name")
                        .column("name")
                        .required()
                        .length(1, 255)
                        .list(sortable().searchable()))
                .field(text("code", "studio.record.code")
                        .column("code")
                        .required()
                        .length(1, 64)
                        .matching("[a-z0-9_-]+")
                        .list(sortable().searchable()))
                .field(instant("modifiedAt", "studio.record.modified_at")
                        .system(SystemColumn.MODIFIED_AT)
                        .list(sortable()));
        List<String> layout = new ArrayList<>(List.of("name", "code"));
        for (Field field : source.fields()) {
            builder.field(field(field, prefix));
            layout.add(field.id());
        }
        builder.section("main", "entity.section.main", layout.toArray(String[]::new))
                .actions("create", "update")
                .archivable()
                .actions("delete")
                .defaultSort("modifiedAt", Entity.Sort.DESC)
                .capabilities(
                        EntityCapability.SAVED_VIEWS,
                        EntityCapability.EXPORT,
                        EntityCapability.HISTORY,
                        EntityCapability.BULK);
        return new EntityDefinitionSource.Declaration(builder.build(), messages(source, prefix));
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

    private Builder field(Field field, String prefix) {
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
            default -> throw new IllegalArgumentException("Unsupported live field type");
        };
        builder.jsonProperty("payload", key);
        if (field.required()) builder.required();
        if (field.maxLength() != null) builder.length(null, field.maxLength());
        if (field.scale() != null) builder.scale(field.scale());
        if (!field.listed()) builder.formOnly();
        return builder;
    }

    private Map<String, Map<String, String>> messages(Declaration source, String prefix) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String language : List.of("ru", "uz", "en")) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(prefix + ".title", label(source.entity().title(), language));
            source.fields()
                    .forEach(field -> values.put(prefix + ".field." + field.id(), label(field.labels(), language)));
            result.put(language, values);
        }
        return result;
    }

    private String label(Labels labels, String language) {
        return switch (language) {
            case "uz" -> labels.uz();
            case "en" -> labels.en();
            default -> labels.ru();
        };
    }
}
