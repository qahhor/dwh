package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.entity.EntityEnums;
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
    private final @Nullable EntityEnums enums;

    ReportExportSources(List<QueryListExporter> exporters, EntityRegistry entities) {
        this(exporters, entities, null);
    }

    ReportExportSources(List<QueryListExporter> exporters, EntityRegistry entities, @Nullable EntityEnums enums) {
        this.entities = entities;
        this.enums = enums;
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

    /**
     * The rows of documents the exporter writes, their enumerations named by the items of their reference lists as they
     * are now, in the language of the file (ADR-0043).
     */
    List<QueryListExporter.Detail> details(QueryListExporter exporter) {
        EntityEnums names = enums;
        if (names == null) return exporter.details();
        return exporter.details().stream()
                .map(detail -> detail.named(names::items))
                .toList();
    }
}
