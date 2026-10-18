package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.FormSection;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.hook.EntityActionHandler;
import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import java.util.ArrayList;
import java.util.HashMap;
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
    public static final String CUSTOM_SECTION = EntityDefinition.CUSTOM_SECTION;

    private static final Set<EntityCapability> NEED_RECORDS =
            Set.of(EntityCapability.HISTORY, EntityCapability.EXPORT, EntityCapability.BULK);

    private final Map<String, EntityDefinition> entities = new TreeMap<>();
    private final Map<String, EntityRecords> records = new TreeMap<>();
    private final Map<String, EntityHooks> hooks;
    private final Map<String, EntityActionHandler> handlers;
    private final List<FormFieldExtender> extenders;
    private final EntityRecordStore runtimeStore;
    private Supplier<LiveEntities> live = LiveEntities::empty;

    @Autowired
    public EntityRegistry(
            List<EntityDefinition> declared,
            List<FormFieldExtender> extenders,
            List<EntityRecords> records,
            List<EntityHooks> hooks,
            List<EntityActionHandler> handlers,
            ObjectProvider<EntityRecordStore> store,
            ObjectProvider<LiveEntities> live) {
        this(declared, extenders, records, hooks, handlers, (Supplier<EntityRecordStore>) store::getObject);
        this.live = live::getObject;
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
        EntityRegistryChecks.relatedLists(entities);
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
        runtimeStore = lazy;
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

    /** A declared entity wins over a live one of the same code, and its lookup never reads the live catalog. */
    public Optional<EntityDefinition> find(String code) {
        EntityDefinition declared = entities.get(code);
        if (declared != null) return Optional.of(resolve(declared));
        return live.get().find(code).map(this::resolve);
    }

    /** The declared entities and the live ones; a live entity under a declared code is left out (ADR-0038). */
    public List<EntityDefinition> all() {
        Map<String, EntityDefinition> all = new TreeMap<>(entities);
        for (EntityDefinition entity : live.get().all()) all.putIfAbsent(entity.code(), entity);
        return List.copyOf(all.values());
    }

    /** The entity whose registry list has this code, if an entity declares it. */
    public Optional<EntityDefinition> findByList(String listCode) {
        return all().stream()
                .filter(entity -> listCode.equals(entity.listCode()))
                .findFirst()
                .map(this::resolve);
    }

    /** How the permission matrix names a form, when an entity with that right declares it (roadmap item 57). */
    public Optional<EntityDefinition.EntityRights> rights(String form) {
        return all().stream()
                .filter(entity -> entity.form().equals(form))
                .flatMap(entity -> Optional.ofNullable(entity.rights()).stream())
                .findFirst();
    }

    public Optional<EntityRecords> records(String code) {
        if (entities.containsKey(code)) return Optional.ofNullable(records.get(code));
        return live.get().find(code).map(runtimeStore::of);
    }

    /**
     * The hooks of the entity, if its module has them (ADR-0032, 6.5); a live entity's come with its declaration
     * (ADR-0043), and a declared entity's lookup never reads the live catalog.
     */
    public Optional<EntityHooks> hooks(String code) {
        EntityHooks declared = hooks.get(code);
        if (declared != null || entities.containsKey(code)) return Optional.ofNullable(declared);
        return live.get().hooks(code);
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
        return all().stream()
                .filter(entity -> entity.capabilities().contains(EntityCapability.HISTORY))
                .map(entity -> historySource(entity, recordsOf(entity)))
                .toList();
    }

    /** The list export of every entity that declares it, under its list's code. */
    public List<QueryListExporter> exporters() {
        return all().stream()
                .filter(entity -> entity.capabilities().contains(EntityCapability.EXPORT))
                .map(entity -> exporter(entity, recordsOf(entity)))
                .toList();
    }

    /** The constructor refused an entity with history, export or bulk actions and no records. */
    private EntityRecords recordsOf(EntityDefinition entity) {
        return entity.model() != null ? runtimeStore.of(entity) : Objects.requireNonNull(records.get(entity.code()));
    }

    /** The dictionary keys that name the fields of the entity's history, as the entity is now. */
    private Map<String, String> historyLabels(EntityDefinition entity) {
        Map<String, String> labels = new LinkedHashMap<>();
        resolve(entity).fields().stream()
                .filter(field -> !field.labelKey().isEmpty())
                .forEach(field -> labels.put(field.key(), field.labelKey()));
        if (entity.capabilities().contains(EntityCapability.ARCHIVE)) {
            labels.put(EntityModel.ARCHIVED, EntityLists.ARCHIVED_LABEL);
        }
        EntityModel model = entity.model();
        if (model != null) {
            // A collection's change is one property of the record's history (ADR-0032, 6.8).
            model.collections().forEach(collection -> labels.put(collection.key(), collection.labelKey()));
        }
        return labels;
    }

    /**
     * The entity's history source. Its fields are named from the entity as it is when the history is read, so a
     * custom field added later is named too, by its own name (plan 10/10, item 5.0). A live form shows only the fields
     * it names: a hidden field keeps its old values in the audit rows (ADR-0041).
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
                return historyLabels(entity);
            }

            @Override
            public boolean namedFieldsOnly() {
                EntityModel model = entity.model();
                return model != null && model.partition() != null;
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
        EntityModel model = entity.model();
        List<QueryListExporter.Detail> details = model == null
                ? List.of()
                : model.collections().stream()
                        .map(collection -> new QueryListExporter.Detail(
                                collection.key(),
                                collection.labelKey(),
                                collection.fields().stream()
                                        .map(field -> EntityListFields.queryField(field, collection.alias()))
                                        .filter(Objects::nonNull)
                                        .toList(),
                                enumerations(collection.fields())))
                        .toList();
        return new QueryListExporter() {
            @Override
            public List<Detail> details() {
                return details;
            }

            @Override
            public boolean visible(Set<String> ids) {
                for (String id : ids) records.requireVisible(Long.parseLong(id));
                return true;
            }

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

    /** The reference lists the enumerations among {@code fields} take their items from, by field key. */
    private static Map<String, String> enumerations(List<EntityField> fields) {
        Map<String, String> references = new HashMap<>();
        for (EntityField field : fields) {
            String reference = field.options().enumeration();
            if (reference != null) references.put(field.key(), reference);
        }
        return references;
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
