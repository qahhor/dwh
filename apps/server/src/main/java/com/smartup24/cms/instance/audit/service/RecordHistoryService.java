package com.smartup24.cms.instance.audit.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 200;

    private final AuditLogService auditLogService;
    private final Map<String, RecordHistorySource> sources;

    /** The modules' own sources and those the declared entities get from their declaration (roadmap item 56). */
    public RecordHistoryService(
            AuditLogService auditLogService, List<RecordHistorySource> sources, EntityRegistry entities) {
        this.auditLogService = auditLogService;
        this.sources = Stream.concat(sources.stream(), entities.historySources().stream())
                .collect(Collectors.toUnmodifiableMap(RecordHistorySource::key, Function.identity()));
    }

    /**
     * A page of the history of one record: {@code limit} 1 to {@link #MAX_LIMIT} (else 422), {@code cursor} the
     * {@code nextCursor} of the previous page (422 when it is not one); the rights are checked first.
     */
    @Transactional(readOnly = true)
    public KeysetPage<HistoryEntry> history(String key, String recordId, Integer limit, String cursor) {
        RecordHistorySource source = sources.get(key);
        if (source == null) {
            throw ApiException.notFound(
                    ErrorCode.NOT_FOUND, "error.audit.history_source_not_found", Map.of("key", String.valueOf(key)));
        }
        if (!SecurityContext.hasPermission(source.form(), source.action())) {
            throw ApiException.permissionDenied(source.form(), source.action());
        }
        source.requireVisible(recordId);

        TimePage page = TimePage.of(limit, cursor, DEFAULT_LIMIT, MAX_LIMIT);
        Labels labels = new Labels(source.fieldLabels(), source.fieldNames(), source.hiddenFields());
        return auditLogService.recordHistory(source.tableName(), recordId, page).map(row -> toEntry(row, labels));
    }

    /** How a page of history names its fields: read from the source once, not per row. */
    private record Labels(Map<String, String> keys, Map<String, String> names, Set<String> hidden) {

        /**
         * A field's place in the source's order — the labelled fields first, as the source lists them, then the
         * named ones; others after them. The audit row's own key order is lost in jsonb.
         */
        int rank(String field) {
            int index = 0;
            for (String key : keys.keySet()) {
                if (key.equals(field)) return index;
                index++;
            }
            for (String key : names.keySet()) {
                if (key.equals(field)) return index;
                index++;
            }
            return Integer.MAX_VALUE;
        }
    }

    /** The kinds of records with a history the viewer may open, for the UI to know where to offer it. */
    public List<String> availableKinds() {
        return sources.values().stream()
                .filter(source -> SecurityContext.hasPermission(source.form(), source.action()))
                .map(RecordHistorySource::key)
                .sorted()
                .toList();
    }

    private HistoryEntry toEntry(AuditLogRepository.AuditRecord row, Labels labels) {
        Map<String, Object> oldRow = camelKeys(row.oldRow());
        Map<String, Object> newRow = camelKeys(row.newRow());
        Set<String> fields = new LinkedHashSet<>();
        fields.addAll(oldRow.keySet());
        fields.addAll(newRow.keySet());
        List<FieldChange> changes = new ArrayList<>();
        for (String field : fields) {
            if (TECHNICAL_FIELDS.contains(field) || labels.hidden().contains(field)) continue;
            Object before = oldRow.get(field);
            Object after = newRow.get(field);
            // An update lists a field only when its value really changed.
            if ("U".equals(row.event()) && Objects.equals(before, after)) continue;
            String labelKey = labels.keys().get(field);
            changes.add(new FieldChange(
                    field,
                    labelKey == null || labelKey.isEmpty() ? null : labelKey,
                    labels.names().get(field),
                    before,
                    after));
        }
        changes.sort(Comparator.comparingInt(change -> labels.rank(change.field())));
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

    /**
     * A field's value before and after. {@code labelKey} names it from the dictionary; a custom field has none and
     * carries its own {@code label} instead (plan 10/10, item 5.0); both are null when the source names neither.
     */
    public record FieldChange(String field, String labelKey, String label, Object oldValue, Object newValue) {}
}
