package com.smartup24.cms.instance.report.export;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.RetainedRecordAccess;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import com.smartup24.cms.instance.common.security.AccessContextFingerprint;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** A finished export retains its original values, but never the original authorization (ADR-0018). */
@Component
public class ExportAccess {
    private final EntityRegistry entities;
    private final RetainedRecordAccess records;
    private final ObjectMapper json;
    private final JsonColumns columns;

    public ExportAccess(EntityRegistry entities, RetainedRecordAccess records, ObjectMapper json) {
        this.entities = entities;
        this.records = records;
        this.json = json;
        this.columns = new JsonColumns(json, "report_exports");
    }

    public record Snapshot(String context, List<String> fields, Set<String> ids) {}

    /** Capture before reading any page; only the rows actually written are added to the proof. */
    public Snapshot start(List<QueryField> fields) {
        return new Snapshot(
                AccessContextFingerprint.current(),
                fields.stream().map(QueryField::key).toList(),
                new LinkedHashSet<>());
    }

    public Map<String, Object> row(Object value, Snapshot snapshot) {
        Map<String, Object> row = json.convertValue(value, new TypeReference<Map<String, Object>>() {});
        snapshot.ids().add(String.valueOf(Objects.requireNonNull(row.get("id"), "export row id")));
        return row;
    }

    public String json(Snapshot snapshot) {
        return columns.object(
                Map.of("context", snapshot.context(), "fields", snapshot.fields(), "ids", snapshot.ids()));
    }

    public void requireCurrent(String storedRequest, QueryList list, QueryListExporter exporter) {
        Map<String, Object> request = columns.readObject(storedRequest);
        Snapshot snapshot = json.convertValue(request.get("access"), Snapshot.class);
        if (snapshot == null
                || !AccessContextFingerprint.current().equals(snapshot.context())
                || snapshot.fields() == null
                || snapshot.ids() == null
                || !list.viewerFields().stream()
                        .map(QueryField::key)
                        .collect(Collectors.toSet())
                        .containsAll(snapshot.fields())) throw denied(list);
        var entity = entities.findByList(list.code()).filter(found -> found.model() != null);
        boolean visible;
        if (entity.isPresent()) {
            Set<Long> ids = snapshot.ids().stream().map(Long::valueOf).collect(Collectors.toSet());
            visible = records.visible(Map.of(entity.get().code(), ids));
        } else {
            visible = exporter.visible(snapshot.ids());
        }
        if (!visible) throw denied(list);
    }

    private static ApiException denied(QueryList list) {
        return ApiException.permissionDenied(list.form(), list.action());
    }
}
