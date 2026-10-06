package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.FormField;
import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.field.QueryRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Resolves relation labels for {@code ref} and {@code multi_ref} fields in batch (ADR-0032, 4.6; plan 10/10, item 5.4).
 * For each referenced entity or system table, collects IDs across all records in the batch and queries labels in a
 * single statement respecting the viewer's data scope, eliminating N+1 lookups on the client.
 */
@Component
public class EntityLabelResolver {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-zA-Z0-9_]{0,63}$");

    private final EntityRegistry registry;
    private final EntityScopes scopes;
    private final @Nullable JdbcClient jdbc;

    public EntityLabelResolver(EntityRegistry registry, EntityScopes scopes, @Nullable JdbcClient jdbc) {
        this.registry = registry;
        this.scopes = scopes;
        this.jdbc = jdbc;
    }

    record RefField(
            String key,
            String targetKey,
            String labelField,
            boolean multi,
            boolean custom,
            @Nullable String collectionKey) {
        String batchKey() {
            return targetKey + "#" + labelField;
        }
    }

    /** Resolves relation labels for a single entity record. */
    public Map<String, Object> resolve(EntityDefinition entity, Map<String, ?> record, long userId) {
        List<Map<String, Object>> result = resolveBatch(entity, List.of(record), userId);
        return result.isEmpty() ? Map.of() : result.getFirst();
    }

    /**
     * Resolves relation labels for a batch of records in one query per target entity and label field.
     */
    public List<Map<String, Object>> resolveBatch(
            EntityDefinition entity, List<? extends Map<String, ?>> records, long userId) {
        if (records.isEmpty()) return List.of();
        List<RefField> fields = findRefFields(entity);
        if (fields.isEmpty()) {
            return records.stream().<Map<String, Object>>map(r -> Map.of()).toList();
        }
        Map<String, Set<Long>> targetIds = collectTargetIds(fields, records);
        if (targetIds.isEmpty()) {
            return records.stream().<Map<String, Object>>map(r -> Map.of()).toList();
        }
        Map<String, Map<Long, String>> loaded = loadLabels(targetIds, userId);
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (Map<String, ?> record : records) {
            result.add(buildRecordLabels(fields, record, loaded));
        }
        return result;
    }

    List<RefField> findRefFields(EntityDefinition entity) {
        List<RefField> list = new ArrayList<>();
        EntityModel model = entity.model();
        if (model != null) {
            collectModelFields(list, model.fields(), null);
            for (EntityCollection col : model.collections()) {
                collectModelFields(list, col.fields(), col.key());
            }
        }
        for (FormField formField : entity.fields()) {
            if (formField.attribute() != null && isRefType(formField.type())) {
                addRefField(
                        list,
                        formField.key(),
                        null,
                        formField.ref(),
                        formField.type() == FieldType.MULTI_REF,
                        true,
                        null);
            }
        }
        return list;
    }

    private void collectModelFields(List<RefField> list, List<EntityField> fields, @Nullable String col) {
        for (EntityField field : fields) {
            if (isRefType(field.type())) {
                addRefField(
                        list,
                        field.key(),
                        field.options().target(),
                        field.options().ref(),
                        field.type() == FieldType.MULTI_REF,
                        false,
                        col);
            }
        }
    }

    private static boolean isRefType(FieldType type) {
        return type == FieldType.REF || type == FieldType.MULTI_REF;
    }

    private void addRefField(
            List<RefField> list,
            String key,
            @Nullable String target,
            @Nullable QueryRef ref,
            boolean multi,
            boolean custom,
            @Nullable String collectionKey) {
        String targetKey = target != null ? target : parseTarget(ref);
        if (targetKey == null) return;
        String labelField =
                ref != null && ref.labelField() != null && !ref.labelField().isBlank() ? ref.labelField() : "name";
        list.add(new RefField(key, targetKey, labelField, multi, custom, collectionKey));
    }

    private static @Nullable String parseTarget(@Nullable QueryRef ref) {
        if (ref == null) return null;
        String path = ref.path();
        if (path.startsWith("/entities/")) {
            return path.substring("/entities/".length());
        }
        if ("/iam/users".equals(path)) {
            return "md.users";
        }
        if ("/iam/org-units".equals(path)) {
            return "sys:org-units";
        }
        if ("/iam/roles".equals(path)) {
            return "sys:roles";
        }
        return null;
    }

    private Map<String, Set<Long>> collectTargetIds(List<RefField> fields, List<? extends Map<String, ?>> records) {
        Map<String, Set<Long>> targetIds = new LinkedHashMap<>();
        for (Map<String, ?> record : records) {
            for (RefField rf : fields) {
                if (rf.collectionKey() == null) {
                    Object val = rf.custom() ? extractCustom(record, rf.key()) : record.get(rf.key());
                    Set<Long> ids = extractIds(val);
                    if (!ids.isEmpty()) {
                        targetIds
                                .computeIfAbsent(rf.batchKey(), k -> new LinkedHashSet<>())
                                .addAll(ids);
                    }
                } else {
                    Object lines = record.get(rf.collectionKey());
                    if (lines instanceof List<?> list) {
                        for (Object rowObj : list) {
                            if (rowObj instanceof Map<?, ?> row) {
                                Set<Long> ids = extractIds(row.get(rf.key()));
                                if (!ids.isEmpty()) {
                                    targetIds
                                            .computeIfAbsent(rf.batchKey(), k -> new LinkedHashSet<>())
                                            .addAll(ids);
                                }
                            }
                        }
                    }
                }
            }
        }
        return targetIds;
    }

    private Map<String, Map<Long, String>> loadLabels(Map<String, Set<Long>> targetIds, long userId) {
        if (jdbc == null) return Map.of();
        Map<String, Map<Long, String>> loaded = new LinkedHashMap<>();
        for (var entry : targetIds.entrySet()) {
            String batchKey = entry.getKey();
            Set<Long> ids = entry.getValue();
            if (ids.isEmpty()) continue;
            int hashIdx = batchKey.indexOf('#');
            String targetKey = hashIdx < 0 ? batchKey : batchKey.substring(0, hashIdx);
            String labelField = hashIdx < 0 ? "name" : batchKey.substring(hashIdx + 1);
            loaded.put(batchKey, queryTarget(targetKey, labelField, ids, userId));
        }
        return loaded;
    }

    private Map<Long, String> queryTarget(String targetKey, String labelField, Set<Long> ids, long userId) {
        if ("sys:org-units".equals(targetKey)) {
            return querySimpleTable("md_org_units", "name", ids);
        }
        if ("sys:roles".equals(targetKey)) {
            return querySimpleTable("md_roles", "name", ids);
        }
        return queryEntity(targetKey, labelField, ids, userId);
    }

    private Map<Long, String> querySimpleTable(String table, String labelCol, Set<Long> ids) {
        String sql = "select id as rid, cast(" + labelCol + " as text) as rlabel from " + table + " where id in (:ids)";
        return executeQuery(sql, Map.of("ids", List.copyOf(ids)));
    }

    private Map<Long, String> queryEntity(String entityCode, String labelField, Set<Long> ids, long userId) {
        var opt = registry.find(entityCode);
        if (opt.isEmpty()) return Map.of();
        EntityDefinition target = opt.get();
        if (!SecurityContext.hasPermission(target.form(), "view")) {
            return Map.of();
        }
        EntityModel model = target.model();
        if (model == null) return Map.of();
        String labelSql = labelSql(model, labelField);
        QueryPlan.SqlFragment scope = scopes.rows(target, userId);
        String sql = "select " + model.alias() + ".id as rid, cast(" + labelSql + " as text) as rlabel from "
                + model.table() + " " + model.alias()
                + " where " + model.alias() + ".id in (:ids)" + scope.sql();
        Map<String, Object> params = new LinkedHashMap<>(scope.params());
        params.put("ids", List.copyOf(ids));
        return executeQuery(sql, params);
    }

    private Map<Long, String> executeQuery(String sql, Map<String, Object> params) {
        if (jdbc == null) return Map.of();
        Map<Long, String> result = new LinkedHashMap<>();
        jdbc.sql(sql)
                .params(params)
                .query((rs, rowNum) -> Map.entry(rs.getLong("rid"), rs.getString("rlabel")))
                .list()
                .forEach(e -> {
                    if (e.getValue() != null) result.put(e.getKey(), e.getValue());
                });
        return result;
    }

    private static String labelSql(EntityModel model, String labelField) {
        for (EntityField f : model.fields()) {
            if (f.key().equals(labelField)) {
                return f.sql(model.alias());
            }
        }
        if (IDENTIFIER.matcher(labelField).matches()) {
            return model.alias() + "." + labelField;
        }
        return model.alias() + ".id";
    }

    private Map<String, Object> buildRecordLabels(
            List<RefField> fields, Map<String, ?> record, Map<String, Map<Long, String>> loaded) {
        Map<String, Object> labels = new LinkedHashMap<>();
        Map<String, List<RefField>> colFields = new LinkedHashMap<>();
        for (RefField rf : fields) {
            if (rf.collectionKey() != null) {
                colFields
                        .computeIfAbsent(rf.collectionKey(), k -> new ArrayList<>())
                        .add(rf);
                continue;
            }
            Object val = rf.custom() ? extractCustom(record, rf.key()) : record.get(rf.key());
            Object resolved = resolveLabel(rf, val, loaded.getOrDefault(rf.batchKey(), Map.of()));
            if (resolved != null) labels.put(rf.key(), resolved);
        }
        buildCollectionLabels(colFields, record, loaded, labels);
        return labels;
    }

    private void buildCollectionLabels(
            Map<String, List<RefField>> colFields,
            Map<String, ?> record,
            Map<String, Map<Long, String>> loaded,
            Map<String, Object> labels) {
        for (var entry : colFields.entrySet()) {
            String colKey = entry.getKey();
            List<RefField> rfs = entry.getValue();
            Object lines = record.get(colKey);
            if (!(lines instanceof List<?> list) || list.isEmpty()) continue;
            List<Map<String, Object>> lineLabels = new ArrayList<>();
            boolean hasAny = false;
            for (Object rowObj : list) {
                Map<String, Object> rowLabels = new LinkedHashMap<>();
                if (rowObj instanceof Map<?, ?> row) {
                    for (RefField rf : rfs) {
                        Object resolved =
                                resolveLabel(rf, row.get(rf.key()), loaded.getOrDefault(rf.batchKey(), Map.of()));
                        if (resolved != null) rowLabels.put(rf.key(), resolved);
                    }
                }
                if (!rowLabels.isEmpty()) hasAny = true;
                lineLabels.add(rowLabels);
            }
            if (hasAny) {
                labels.put(colKey, lineLabels);
            }
        }
    }

    private static @Nullable Object resolveLabel(RefField rf, @Nullable Object val, Map<Long, String> targetMap) {
        if (val == null) return null;
        if (rf.multi()) {
            List<String> listLabels = new ArrayList<>();
            for (Long id : extractIds(val)) {
                String lbl = targetMap.get(id);
                if (lbl != null) listLabels.add(lbl);
            }
            return listLabels.isEmpty() ? null : listLabels;
        }
        Set<Long> ids = extractIds(val);
        return ids.isEmpty() ? null : targetMap.get(ids.iterator().next());
    }

    private static @Nullable Object extractCustom(Map<String, ?> record, String key) {
        Object attrs = record.get("attributes");
        if (attrs instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return record.get(key);
    }

    private static Set<Long> extractIds(@Nullable Object value) {
        Set<Long> ids = new LinkedHashSet<>();
        if (value == null) return ids;
        Iterable<?> items = value instanceof Collection<?> col ? col : List.of(value);
        for (Object item : items) {
            if (item instanceof Number num) {
                ids.add(num.longValue());
            } else if (item != null) {
                String str = item.toString().strip();
                if (!str.isEmpty() && str.chars().allMatch(Character::isDigit)) {
                    ids.add(Long.parseLong(str));
                }
            }
        }
        return ids;
    }
}
