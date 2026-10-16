package com.smartup24.cms.instance.studio.service;

import com.smartup24.cms.instance.common.entity.EntityDefinitionSource;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** A fresh authoritative read per request, pinned for all its consumers; no notification dependence (ADR-0038). */
@Component
@Profile("!migrate")
public class StudioDefinitions implements EntityDefinitionSource {
    private final StudioFormRepository repository;
    private final StudioEntityCompiler compiler;
    private final String snapshotKey = StudioDefinitions.class.getName() + "." + System.identityHashCode(this);

    public StudioDefinitions(StudioFormRepository repository, StudioEntityCompiler compiler) {
        this.repository = repository;
        this.compiler = compiler;
    }

    @Override
    public Map<String, Declaration> snapshot() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) return load();
        Object existing = request.getAttribute(snapshotKey, RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof Snapshot snapshot) return snapshot.declarations();
        Map<String, Declaration> result = load();
        request.setAttribute(snapshotKey, new Snapshot(result), RequestAttributes.SCOPE_REQUEST);
        return result;
    }

    private Map<String, Declaration> load() {
        var rows = repository.all();
        if (rows.size() > StudioActivationService.MAX_FORMS)
            throw new IllegalStateException("Live form quota exceeded");
        Map<String, Declaration> result = new LinkedHashMap<>();
        for (var row : rows) {
            Declaration value = compiler.compile(row.id(), row.declaration());
            if (!row.code().equals(value.entity().code()) || result.putIfAbsent(row.code(), value) != null) {
                throw new IllegalStateException("Invalid live form catalog");
            }
        }
        return Map.copyOf(result);
    }

    private record Snapshot(Map<String, Declaration> declarations) {}
}
