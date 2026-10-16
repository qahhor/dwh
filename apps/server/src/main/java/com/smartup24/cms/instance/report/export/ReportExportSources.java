package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Resolves freshly activated entity exporters alongside explicit module exporters (ADR-0038). */
final class ReportExportSources {
    private final EntityRegistry entities;
    private final Map<String, QueryListExporter> declared;

    ReportExportSources(List<QueryListExporter> exporters, EntityRegistry entities) {
        this.entities = entities;
        declared = Stream.concat(exporters.stream(), entities.exporters().stream())
                .collect(Collectors.toUnmodifiableMap(QueryListExporter::code, Function.identity()));
    }

    @Nullable
    QueryListExporter find(String code) {
        return entities.exporters().stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElse(declared.get(code));
    }
}
