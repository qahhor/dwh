package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityDefinition.FormSection;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every declared entity by code (ADR-0019, 2.1). An entity is handed out with its custom fields as they are now:
 * the extenders' fields follow the declared ones in a section of their own, and a custom field whose key a
 * declared field already uses is left out.
 *
 * <p>What an entity's capabilities promise is built here from its declaration and its module's
 * {@link EntityRecords} (roadmap item 56): the history source, the list export and the bulk delete. An entity
 * that declares one of them without the records bean fails the start, not the first request.
 */
@Component
public class EntityRegistry {

    /** The section the custom fields go to. */
    public static final String CUSTOM_SECTION = "custom";

    private static final Set<EntityCapability> NEED_RECORDS =
            Set.of(EntityCapability.HISTORY, EntityCapability.EXPORT, EntityCapability.BULK);

    private final Map<String, EntityDefinition> entities = new TreeMap<>();
    private final Map<String, EntityRecords> records = new TreeMap<>();
    private final List<FormFieldExtender> extenders;

    @Autowired
    public EntityRegistry(
            List<EntityDefinition> declared, List<FormFieldExtender> extenders, List<EntityRecords> records) {
        for (EntityDefinition entity : declared) {
            if (entities.put(entity.code(), entity) != null) {
                throw new IllegalStateException("Duplicate entity " + entity.code());
            }
        }
        for (EntityRecords one : records) {
            if (!entities.containsKey(one.entity())) {
                throw new IllegalStateException("Records of an undeclared entity " + one.entity());
            }
            if (this.records.put(one.entity(), one) != null) {
                throw new IllegalStateException("Duplicate records of entity " + one.entity());
            }
        }
        for (EntityDefinition entity : entities.values()) {
            if (!this.records.containsKey(entity.code())
                    && entity.capabilities().stream().anyMatch(NEED_RECORDS::contains)) {
                throw new IllegalStateException("Entity " + entity.code()
                        + " declares history, export or bulk actions without its EntityRecords");
            }
        }
        this.extenders = List.copyOf(extenders);
    }

    public EntityRegistry(List<EntityDefinition> declared, List<FormFieldExtender> extenders) {
        this(declared, extenders, List.of());
    }

    public EntityRegistry(List<EntityDefinition> declared) {
        this(declared, List.of(), List.of());
    }

    public Optional<EntityDefinition> find(String code) {
        return Optional.ofNullable(entities.get(code)).map(this::resolve);
    }

    public List<EntityDefinition> all() {
        return List.copyOf(entities.values());
    }

    /** How the permission matrix names a form, when an entity with that right declares it (roadmap item 57). */
    public Optional<EntityDefinition.EntityRights> rights(String form) {
        return entities.values().stream()
                .filter(entity -> entity.form().equals(form) && entity.rights() != null)
                .map(EntityDefinition::rights)
                .findFirst();
    }

    public Optional<EntityRecords> records(String code) {
        return Optional.ofNullable(records.get(code));
    }

    /** The entity with its custom fields added in the {@value #CUSTOM_SECTION} section. */
    public EntityDefinition resolve(EntityDefinition entity) {
        if (extenders.isEmpty() || !entity.capabilities().contains(EntityCapability.CUSTOM_FIELDS)) return entity;
        Set<String> taken = new HashSet<>(entity.fieldsByKey().keySet());
        List<FormField> extra = extenders.stream()
                .flatMap(extender -> extender.extraFields(entity).stream())
                .filter(field -> taken.add(field.key()))
                .toList();
        if (extra.isEmpty()) return entity;
        List<FormField> fields = new ArrayList<>(entity.fields());
        fields.addAll(extra);
        List<FormSection> layout = new ArrayList<>(entity.layout());
        layout.add(new FormSection(
                CUSTOM_SECTION,
                "entity.section.custom",
                extra.stream().map(FormField::key).toList()));
        return new EntityDefinition(
                entity.code(),
                entity.form(),
                entity.listCode(),
                entity.customEntity(),
                entity.auditTable(),
                entity.rights(),
                entity.menu(),
                fields,
                layout,
                entity.actions(),
                entity.capabilities());
    }

    /**
     * The history of every entity that declares it, under the entity's code: opened with {@code form.view}, for a
     * record the viewer may see, its fields named by the form's labels.
     */
    public List<RecordHistorySource> historySources() {
        return entities.values().stream()
                .filter(entity -> entity.capabilities().contains(EntityCapability.HISTORY))
                .map(entity -> historySource(entity, records.get(entity.code())))
                .toList();
    }

    /** The list export of every entity that declares it, under its list's code. */
    public List<QueryListExporter> exporters() {
        return entities.values().stream()
                .filter(entity -> entity.capabilities().contains(EntityCapability.EXPORT))
                .map(entity -> exporter(entity, records.get(entity.code())))
                .toList();
    }

    private static RecordHistorySource historySource(EntityDefinition entity, EntityRecords records) {
        Map<String, String> labels = new TreeMap<>();
        entity.fields().forEach(field -> labels.put(field.key(), field.labelKey()));
        return new RecordHistorySource() {
            public String key() {
                return entity.code();
            }

            public String tableName() {
                return entity.auditTable();
            }

            public String form() {
                return entity.form();
            }

            public String action() {
                return "view";
            }

            public Map<String, String> fieldLabels() {
                return labels;
            }

            public void requireVisible(String recordId) {
                long id;
                try {
                    id = Long.parseLong(recordId);
                } catch (NumberFormatException e) {
                    throw ApiException.notFound(ErrorCode.NOT_FOUND, "Запись не найдена");
                }
                records.requireVisible(id);
            }
        };
    }

    private static QueryListExporter exporter(EntityDefinition entity, EntityRecords records) {
        return new QueryListExporter() {
            public String code() {
                return entity.listCode();
            }

            public KeysetPage<?> page(
                    int limit, String cursor, String filter, String sort, String search, Map<String, String> options) {
                return records.page(limit, cursor, filter, sort, search);
            }
        };
    }
}
