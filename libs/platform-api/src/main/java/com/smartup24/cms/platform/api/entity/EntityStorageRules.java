package com.smartup24.cms.platform.api.entity;

import com.smartup24.cms.platform.api.entity.collection.EntityCollection;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import com.smartup24.cms.platform.api.entity.importing.EntityImportSpec;
import com.smartup24.cms.platform.api.entity.search.EntitySearchSpec;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Rejects declarations the shared-table storage cannot safely execute (ADR-0038). */
final class EntityStorageRules {
    private static final Set<String> SYSTEM = Set.of(
            "id",
            "revision",
            "created_at",
            "created_by",
            "modified_at",
            "modified_by",
            "archived_at",
            "archived_by",
            "attributes");

    private static final Set<FieldType> TYPES = Set.of(
            FieldType.TEXT,
            FieldType.TEXTAREA,
            FieldType.NUMBER,
            FieldType.DATE,
            FieldType.DATETIME,
            FieldType.TIME,
            FieldType.BOOLEAN,
            FieldType.EMAIL,
            FieldType.PHONE,
            FieldType.URL);

    private EntityStorageRules() {}

    static void check(
            List<EntityField> fields,
            @Nullable EntityPartition partition,
            List<EntityCollection> collections,
            @Nullable EntityReference reference,
            @Nullable EntityImportSpec importing,
            @Nullable EntitySearchSpec search) {
        Set<String> columns = new HashSet<>();
        Set<String> properties = new HashSet<>();
        Set<String> payloads = new HashSet<>();
        if (partition != null && (!collections.isEmpty() || reference != null || importing != null || search != null)) {
            throw new IllegalArgumentException(
                    "A partitioned entity has no collections, reference, import or global search");
        }
        for (EntityField field : fields) {
            if (field.source() instanceof FieldSource.Column column) columns.add(column.name());
            if (field.source() instanceof FieldSource.JsonProperty property) {
                if (SYSTEM.contains(property.column()) || !properties.add(property.column() + "." + property.key())) {
                    throw new IllegalArgumentException(
                            "A JSON property cannot shadow a system column or another property");
                }
                payloads.add(property.column());
            }
            if (partition != null && !TYPES.contains(field.type())) {
                throw new IllegalArgumentException("A partitioned entity holds only simple scalar fields");
            }
            if (partition != null
                    && !(field.source() instanceof FieldSource.Column
                            || field.source() instanceof FieldSource.SystemValue
                            || field.source() instanceof FieldSource.JsonProperty)) {
                throw new IllegalArgumentException(
                        "A partitioned entity uses only columns, JSON properties and system values");
            }
        }
        if (payloads.stream().anyMatch(columns::contains)
                || (partition != null
                        && (columns.contains(partition.column()) || payloads.contains(partition.column())))) {
            throw new IllegalArgumentException(
                    "A field cannot overwrite a JSON container or the server-owned partition");
        }
    }
}
