package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.entity.store.EntityStoreRepository;
import com.smartup24.cms.instance.common.security.SecurityContext;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Rechecks the records behind retained responses with the live scope (ADR-0013, ADR-0032). */
@Component
public class RetainedRecordAccess {

    private static final ThreadLocal<Map<String, Set<Long>>> CAPTURE = new ThreadLocal<>();

    private final EntityGate gate;
    private final EntityScopes scopes;
    private final EntityStoreRepository store;

    public RetainedRecordAccess(EntityGate gate, EntityScopes scopes, EntityStoreRepository store) {
        this.gate = gate;
        this.scopes = scopes;
        this.store = store;
    }

    /** Records returned to a caller, only while the idempotency filter captures an answer. */
    public static void remember(String entity, long id) {
        Map<String, Set<Long>> capture = CAPTURE.get();
        if (capture != null)
            capture.computeIfAbsent(entity, unused -> new LinkedHashSet<>()).add(id);
    }

    /** A request owns its capture and always releases the thread-local, including on a failed transaction. */
    public static Capture capture() {
        return new Capture();
    }

    public static Map<String, Set<Long>> captured() {
        Map<String, Set<Long>> capture = CAPTURE.get();
        if (capture == null) return Map.of();
        Map<String, Set<Long>> result = new LinkedHashMap<>();
        capture.forEach((code, ids) -> result.put(code, Set.copyOf(ids)));
        return Map.copyOf(result);
    }

    public static final class Capture implements AutoCloseable {
        private final Map<String, Set<Long>> previous = CAPTURE.get();

        private Capture() {
            CAPTURE.set(new LinkedHashMap<>());
        }

        @Override
        public void close() {
            if (previous == null) CAPTURE.remove();
            else CAPTURE.set(previous);
        }
    }

    /**
     * One scoped query per entity, after the caller checks the original permission context. This does not add the
     * entity's view right to endpoints that authorize another operation, such as IAM assignment or task comments.
     */
    public boolean visible(Map<String, Set<Long>> records) {
        Long user = SecurityContext.getCurrentUserId();
        if (user == null) return records.isEmpty();
        for (var entry : records.entrySet()) {
            var entity = gate.findActive(entry.getKey()).filter(found -> found.model() != null);
            if (entity.isEmpty()) return false;
            if (!entry.getValue().isEmpty()
                    && !store.visibleRows(entity.get(), entry.getValue(), scopes.rows(entity.get(), user))
                            .keySet()
                            .containsAll(entry.getValue())) return false;
        }
        return true;
    }
}
