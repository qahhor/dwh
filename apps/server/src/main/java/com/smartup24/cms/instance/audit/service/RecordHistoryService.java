package com.smartup24.cms.instance.audit.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The change history of one record for its card (ADR-0017): the audit rows of
 * that record, newest first, each turned into the fields that changed with
 * their old and new values. The history opens only with the record's own right
 * and only for a record the viewer can see; values are redacted as in the
 * audit log, and technical fields are left out.
 */
@Service
public class RecordHistoryService {

    /** Bookkeeping every table has; it says nothing to the person reading the history. */
    private static final Set<String> TECHNICAL_FIELDS =
            Set.of("id", "companyId", "lockVersion", "createdAt", "createdBy", "modifiedAt", "modifiedBy", "updatedAt");

    private final AuditLogService auditLogService;
    private final Map<String, RecordHistorySource> sources;

    /** The modules' own sources and those the declared entities get from their declaration (roadmap item 56). */
    public RecordHistoryService(
            AuditLogService auditLogService, List<RecordHistorySource> sources, EntityRegistry entities) {
        this.auditLogService = auditLogService;
        this.sources = Stream.concat(sources.stream(), entities.historySources().stream())
                .collect(Collectors.toUnmodifiableMap(RecordHistorySource::key, Function.identity()));
    }

    @Transactional(readOnly = true)
    public KeysetPage<HistoryEntry> history(String key, String recordId, int limit, String cursor) {
        RecordHistorySource source = sources.get(key);
        if (source == null) {
            throw ApiException.notFound(
                    ErrorCode.NOT_FOUND, "error.audit.history_source_not_found", Map.of("key", String.valueOf(key)));
        }
        if (!SecurityContext.hasPermission(source.form(), source.action())) {
            throw ApiException.permissionDenied(source.form(), source.action());
        }
        source.requireVisible(recordId);

        KeysetPage<AuditLogRepository.AuditRecord> page =
                auditLogService.listAuditLogs(source.tableName(), recordId, null, null, null, null, limit, cursor);
        List<HistoryEntry> entries =
                page.items().stream().map(row -> toEntry(row, source)).toList();
        return KeysetPage.of(entries, page.nextCursor(), page.hasMore(), page.totalEstimated());
    }

    /** The kinds of records with a history the viewer may open, for the UI to know where to offer it. */
    public List<String> availableKinds() {
        return sources.values().stream()
                .filter(source -> SecurityContext.hasPermission(source.form(), source.action()))
                .map(RecordHistorySource::key)
                .sorted()
                .toList();
    }

    private HistoryEntry toEntry(AuditLogRepository.AuditRecord row, RecordHistorySource source) {
        Map<String, Object> oldRow = camelKeys(row.oldRow());
        Map<String, Object> newRow = camelKeys(row.newRow());
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(oldRow.keySet());
        fields.addAll(newRow.keySet());
        List<FieldChange> changes = new ArrayList<>();
        for (String field : fields) {
            if (TECHNICAL_FIELDS.contains(field) || source.hiddenFields().contains(field)) continue;
            Object before = oldRow.get(field);
            Object after = newRow.get(field);
            // An update lists a field only when its value really changed.
            if ("U".equals(row.event()) && Objects.equals(before, after)) continue;
            changes.add(new FieldChange(field, source.fieldLabels().get(field), before, after));
        }
        return new HistoryEntry(
                row.id(),
                row.event(),
                row.changedAt(),
                row.changedBy(),
                row.changedByName(),
                row.changedByLogin(),
                row.isApi(),
                changes);
    }

    private static Map<String, Object> camelKeys(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.putIfAbsent(camel(key), value));
        return result;
    }

    static String camel(String key) {
        if (key.indexOf('_') < 0) return key;
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : key.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = result.length() > 0;
            } else {
                result.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return result.toString();
    }

    /** One change of the record: who, when, how (web or API) and which fields. */
    public record HistoryEntry(
            Long id,
            String event,
            Instant changedAt,
            Long changedBy,
            String changedByName,
            String changedByLogin,
            boolean isApi,
            List<FieldChange> changes) {}

    /** A field's value before and after; the label key is null when the source names none. */
    public record FieldChange(String field, String labelKey, Object oldValue, Object newValue) {}
}
