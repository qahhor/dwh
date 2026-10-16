package com.smartup24.cms.instance.common.entity.report;

import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import com.smartup24.cms.platform.api.entity.field.FieldSource;
import com.smartup24.cms.platform.api.entity.field.FieldType;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The declaration admits only a real reference and scalar target properties (ADR-0039). */
final class EntityReportDatasetRules {
    private static final Set<FieldType> TYPES =
            Set.of(FieldType.SELECT, FieldType.BOOLEAN, FieldType.DATE, FieldType.DATETIME);

    private EntityReportDatasetRules() {}

    static void validate(EntityReportDataset dataset, Map<String, EntityDefinition> entities) {
        EntityDefinition root = entities.get(dataset.rootEntity());
        if (root == null) throw new IllegalArgumentException("Unknown dataset root entity");
        EntityField reference = reference(dataset, root);
        EntityDefinition target = entities.get(reference.options().target());
        if (target == null) throw new IllegalArgumentException("Unknown dataset target entity");
        EntityModel targetModel = model(target);
        var keys = new HashSet<String>();
        EntityLists.queryList(root).fields().forEach(field -> keys.add(field.key()));
        for (var projection : dataset.projections()) {
            if (!keys.add(projection.key())) throw new IllegalArgumentException("Dataset shadows a root field");
            EntityField field = field(targetModel, projection.field());
            if (!TYPES.contains(field.type()) || !scalar(field.source()))
                throw new IllegalArgumentException("Unsupported dataset target field");
        }
    }

    static EntityField reference(EntityReportDataset dataset, EntityDefinition root) {
        EntityField reference = field(model(root), dataset.reference());
        if (reference.type() != FieldType.REF
                || !(reference.source() instanceof FieldSource.Column)
                || reference.options().target() == null)
            throw new IllegalArgumentException("A dataset needs a declared single column REF target");
        return reference;
    }

    static EntityModel model(EntityDefinition entity) {
        if (entity == null || entity.model() == null)
            throw new IllegalArgumentException("Dataset entity must have a model");
        return entity.model();
    }

    static EntityField field(EntityModel model, String key) {
        return model.fields().stream()
                .filter(field -> field.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dataset declaration field"));
    }

    private static boolean scalar(FieldSource source) {
        return source instanceof FieldSource.Column
                || source instanceof FieldSource.SystemValue
                || source instanceof FieldSource.JsonProperty;
    }
}
