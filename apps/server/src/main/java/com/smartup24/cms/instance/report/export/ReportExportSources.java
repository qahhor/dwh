package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the entities' exporters from the registry at each call and the modules' own exporters kept from the start
 * (ADR-0038): an entity exporter is never kept, so a live form that is quarantined, stops loading or whose module is
 * switched off exports nothing.
 */
final class ReportExportSources {
    private final EntityRegistry entities;
    private final Map<String, QueryListExporter> modules;

    ReportExportSources(List<QueryListExporter> exporters, EntityRegistry entities) {
        this.entities = entities;
        modules =
                exporters.stream().collect(Collectors.toUnmodifiableMap(QueryListExporter::code, Function.identity()));
    }

    @Nullable
    QueryListExporter find(String code) {
        return entities.exporters().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElse(modules.get(code));
    }
}
