package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityDefinition.FormSection;
import com.smartup24.cms.instance.common.entity.hook.EntityActionHandler;
import com.smartup24.cms.instance.common.entity.hook.EntityHooks;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Every declared entity by code (ADR-0019, 2.1). An entity is handed out with its custom fields as they are now:
 * the extenders' fields follow the declared ones in a section of their own, and a custom field whose key a
 * declared field already uses is left out.
 *
 * <p>What an entity's capabilities promise is built here from its declaration (roadmap item 56): the history source,
 * the list export and the bulk actions. The records behind them are the general runtime's for an entity with a table
 * ({@link EntityRecordStore}, ADR-0032, 6.5) and the module's {@link EntityRecords} bean for an entity without one. The
 * hooks ({@link EntityHooks}) and the action handlers ({@link EntityActionHandler}) of the entities are collected here
 * too. What does not fit — a records bean for an entity the runtime serves, hooks of an undeclared entity, a second
 * one, a declared action without its handler — fails the start, not the first request ({@link EntityRegistryChecks}).
 */
@Component
public class EntityRegistry {

    /** The section the custom fields go to. */
    public static final String CUSTOM_SECTION = "custom";

    private static final Set<EntityCapability> NEED_RECORDS =
            Set.of(EntityCapability.HISTORY, EntityCapability.EXPORT, EntityCapability.BULK);

    private final Map<String, EntityDefinition> entities = new TreeMap<>();
    private final Map<String, EntityRecords> records = new TreeMap<>();
    private final Map<String, EntityHooks> hooks;
    private final Map<String, EntityActionHandler> handlers;
    private final List<FormFieldExtender> extenders;

    @Autowired
    public EntityRegistry(
            List<EntityDefinition> declared,
            List<FormFieldExtender> extenders,
            List<EntityRecords> records,
            List<EntityHooks> hooks,
            List<EntityActionHandler> handlers,
            ObjectProvider<EntityRecordStore> store) {
        this(declared, extenders, records, hooks, handlers, (Supplier<EntityRecordStore>) store::getObject);
    }

    /** The registry over a store given directly; the runtime is created after the registry, so it is asked lazily. */
    public EntityRegistry(
            List<EntityDefinition> declared,
            List<FormFieldExtender> extenders,
            List<EntityRecords> records,
            List<EntityHooks> hooks,
            List<EntityActionHandler> handlers,
            Supplier<EntityRecordStore> store) {
        for (EntityDefinition entity : declared) {
            if (entities.put(entity.code(), entity) != null) {
                throw new IllegalStateException("Duplicate entity " + entity.code());
            }
        }
        EntityRegistryChecks.references(entities);
        for (EntityRecords one : records) {
            EntityDefinition entity = entities.get(one.entity());
            if (entity == null) {
                throw new IllegalStateException("Records of an undeclared entity " + one.entity());
            }
            if (entity.model() != null) {
                throw new IllegalStateException("Entity " + one.entity()
                        + " has a table: the general runtime keeps its records (ADR-0032, 6.5)");
            }
            if (this.records.put(one.entity(), one) != null) {
                throw new IllegalStateException("Duplicate records of entity " + one.entity());
            }
        }
        LazyStore lazy = new LazyStore(store);
        for (EntityDefinition entity : entities.values()) {
            if (entity.model() != null) {
                this.records.put(entity.code(), lazy.of(entity));
            } else if (entity.capabilities().stream().anyMatch(NEED_RECORDS::contains)
                    && !this.records.containsKey(entity.code())) {
                throw new IllegalStateException("Entity " + entity.code()
                        + " declares history, export or bulk actions without its EntityRecords");
            }
        }
        this.hooks = EntityRegistryChecks.hooks(entities, hooks);
        this.handlers = EntityRegistryChecks.handlers(entities, handlers);
        this.extenders = List.copyOf(extenders);
    }

    /** A registry without records, hooks or a runtime: declarations and their forms only. */
    public EntityRegistry(List<EntityDefinition> declared, List<FormFieldExtender> extenders) {
        this(declared, extenders, List.of(), List.of(), List.of(), EntityRegistry::noStore);
    }

    public EntityRegistry(List<EntityDefinition> declared) {
        this(declared, List.of());
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
                .filter(entity -> entity.form().equals(form))
                .flatMap(entity -> Optional.ofNullable(entity.rights()).stream())
                .findFirst();
    }

    public Optional<EntityRecords> records(String code) {
        return Optional.ofNullable(records.get(code));
    }

    /** The hooks of the entity, if its module has them (ADR-0032, 6.5). */
    public Optional<EntityHooks> hooks(String code) {
        return Optional.ofNullable(hooks.get(code));
    }

    /** The handler of a declared record action (ADR-0032, 6.7). */
    public Optional<EntityActionHandler> handler(String code, String action) {
        return Optional.ofNullable(handlers.get(EntityRegistryChecks.handlerKey(code, action)));
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
        return entity.withForm(fields, layout);
    }

    /**
     * The history of every entity that declares it, under the entity's code: opened with {@code form.view}, for a
     * record the viewer may see, its fields named by the form's labels.
     */
    public List<RecordHistorySource> historySources() {
        return entities.values().stream()
                .filter(entity -> entity.capabilities().contains(EntityCapability.HISTORY))
                .map(entity -> historySource(entity, recordsOf(entity)))
                .toList();
    }

    /** The list export of every entity that declares it, under its list's code. */
    public List<QueryListExporter> exporters() {
        return entities.values().stream()
                .filter(entity -> entity.capabilities().contains(EntityCapability.EXPORT))
                .map(entity -> exporter(entity, recordsOf(entity)))
                .toList();
    }

    /** The constructor refused an entity with history, export or bulk actions and no records. */
    private EntityRecords recordsOf(EntityDefinition entity) {
        return Objects.requireNonNull(records.get(entity.code()), entity.code());
    }

    /**
     * The entity's history source. Its fields are named from the entity as it is when the history is read, so a
     * custom field added later is named too, by its own name (plan 10/10, item 5.0).
     */
    private RecordHistorySource historySource(EntityDefinition entity, EntityRecords records) {
        return new RecordHistorySource() {
            @Override
            public String key() {
                return entity.code();
            }

            @Override
            public String tableName() {
                return Objects.requireNonNull(entity.auditTable(), entity.code());
            }

            @Override
            public String form() {
                return entity.form();
            }

            @Override
            public String action() {
                return "view";
            }

            @Override
            public Map<String, String> fieldLabels() {
                Map<String, String> labels = new LinkedHashMap<>();
                resolve(entity).fields().stream()
                        .filter(field -> !field.labelKey().isEmpty())
                        .forEach(field -> labels.put(field.key(), field.labelKey()));
                if (entity.capabilities().contains(EntityCapability.ARCHIVE)) {
                    labels.put(EntityModel.ARCHIVED, EntityLists.ARCHIVED_LABEL);
                }
                return labels;
            }

            @Override
            public Map<String, String> fieldNames() {
                Map<String, String> names = new LinkedHashMap<>();
                resolve(entity).fields().stream()
                        .filter(field -> field.labelKey().isEmpty() && field.label() != null)
                        .forEach(field -> names.put(field.key(), field.label()));
                return names;
            }

            // The fields that do not exist for the viewer stay out of their history (ADR-0032, 5.2).
            @Override
            public Set<String> hiddenFields() {
                return EntityFieldRights.hidden(entity);
            }

            @Override
            public void requireVisible(String recordId) {
                long id;
                try {
                    id = Long.parseLong(recordId);
                } catch (NumberFormatException e) {
                    throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found");
                }
                records.requireVisible(id);
            }
        };
    }

    private static QueryListExporter exporter(EntityDefinition entity, EntityRecords records) {
        return new QueryListExporter() {
            @Override
            public String code() {
                return Objects.requireNonNull(entity.listCode(), entity.code());
            }

            @Override
            public KeysetPage<?> page(
                    int limit, String cursor, String filter, String sort, String search, Map<String, String> options) {
                return records.page(limit, cursor, filter, sort, search);
            }
        };
    }

    private static EntityRecordStore noStore() {
        throw new IllegalStateException("This registry has no entity runtime");
    }

    /** The runtime's store, asked for at the first use: it is created after the registry. */
    private static final class LazyStore implements EntityRecordStore {

        private final Supplier<EntityRecordStore> store;

        private LazyStore(Supplier<EntityRecordStore> store) {
            this.store = store;
        }

        @Override
        public void requireVisible(EntityDefinition entity, long id) {
            store.get().requireVisible(entity, id);
        }

        @Override
        public KeysetPage<?> page(
                EntityDefinition entity, int limit, String cursor, String filter, String sort, String search) {
            return store.get().page(entity, limit, cursor, filter, sort, search);
        }

        @Override
        public void delete(EntityDefinition entity, long id) {
            store.get().delete(entity, id);
        }

        @Override
        public void archive(EntityDefinition entity, long id) {
            store.get().archive(entity, id);
        }

        @Override
        public void change(EntityDefinition entity, long id, String action, JsonNode params) {
            store.get().change(entity, id, action, params);
        }
    }
}
