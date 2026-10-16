package com.smartup24.cms.instance.common.entity.store;

import com.smartup24.cms.platform.api.entity.EntityModel;
import com.smartup24.cms.platform.api.entity.EntityPartition;
import java.util.List;
import java.util.Map;

/** Applies the server-owned partition to every storage operation (ADR-0038). */
final class EntityPartitions {
    private static final String PARAMETER = "entityPartition";

    private EntityPartitions() {}

    static String where(EntityModel model, String prefix, Map<String, Object> params) {
        EntityPartition partition = model.partition();
        if (partition == null) return "";
        params.put(PARAMETER, partition.value());
        return " and " + prefix + partition.column() + " = :" + PARAMETER;
    }

    static void insert(EntityModel model, List<String> names, List<String> values, Map<String, Object> params) {
        EntityPartition partition = model.partition();
        if (partition == null) return;
        names.add(partition.column());
        values.add(":" + PARAMETER);
        params.put(PARAMETER, partition.value());
    }
}
