package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityRecordStore;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.hook.EntityActionHandler;
import com.smartup24.cms.instance.common.entity.workflow.EntityTransition;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisions;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The general runtime of the entities (ADR-0032, 6; plan 10/10, item 5.4): what {@code /api/v1/entities/{code}} does
 * for every entity declared with a table. Steps 1–3 of ADR-0032, 6.3 — the entity, the viewer's rights, the revision of
 * If-Match — run before the transaction, so a refused request holds no connection; the rest runs in one transaction
 * ({@code REQUIRED}): with an Idempotency-Key the filter's, so the change and its stored answer commit together
 * (ADR-0032, 6.4). It is also the {@link EntityRecordStore} of the registry: the history, export, bulk actions and file
 * reads of such an entity go through the same checks.
 */
@Component
public class EntityRuntime implements EntityRecordStore {

    private final EntityGate gate;
    private final EntityRegistry registry;
    private final EntityReads reads;
    private final EntityWrites writes;
    private final TransactionTemplate changes;
    private final TransactionTemplate reading;

    public EntityRuntime(
            EntityGate gate,
            EntityRegistry registry,
            EntityReads reads,
            EntityWrites writes,
            PlatformTransactionManager transactions) {
        this.gate = gate;
        this.registry = registry;
        this.reads = reads;
        this.writes = writes;
        this.changes = new TransactionTemplate(transactions);
        this.changes.setName("entity-runtime");
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        this.reading.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
    }

    /** {@code GET /api/v1/entities/{code}}: a page of the list for the viewer (ADR-0016). */
    public KeysetPage<EntityRecordView> list(
            String code,
            @Nullable Integer limit,
            @Nullable String cursor,
            @Nullable String filter,
            @Nullable String sort,
            @Nullable String search) {
        EntityDefinition entity = gate.viewable(code);
        KeysetPage<Map<String, Object>> page = read(() -> reads.page(entity, limit, cursor, filter, sort, search));
        return page.map(record -> new EntityRecordView(record, EntityReads.actions(entity, record)));
    }

    /** {@code GET /api/v1/entities/{code}/{id}}: the record in the viewer's scope, an archived one too. */
    public EntityRecordView get(String code, long id) {
        EntityDefinition entity = gate.viewable(code);
        return EntityReads.view(entity, read(() -> reads.visible(entity, id, false)));
    }

    /** {@code POST /api/v1/entities/{code}}: creates a record. */
    public EntityRecordView create(String code, @Nullable JsonNode body) {
        EntityDefinition entity = gate.allowed(code, "create");
        return EntityReads.view(entity, change(() -> writes.create(entity, body)));
    }

    /**
     * {@code PATCH /api/v1/entities/{code}/{id}}: changes the fields the body names, from the revision {@code If-Match}
     * names (step 3: 428 without it).
     */
    public EntityRecordView update(String code, long id, @Nullable String ifMatch, @Nullable JsonNode body) {
        EntityDefinition entity = gate.allowed(code, "update");
        long revision = Revisions.required(ifMatch);
        return EntityReads.view(entity, change(() -> writes.update(entity, id, revision, body)));
    }

    /** {@code DELETE /api/v1/entities/{code}/{id}}: with {@code If-Match}, only from its revision (ADR-0032, 5.3). */
    public void delete(String code, long id, @Nullable String ifMatch) {
        EntityDefinition entity = gate.allowed(code, EntityDefinition.DELETE);
        Long revision = Revisions.optional(ifMatch);
        change(() -> {
            writes.delete(entity, id, revision);
            return Map.of();
        });
    }

    /** {@code PUT /api/v1/entities/{code}/{id}/archived}: archives or restores, from the revision named. */
    public EntityRecordView archive(String code, long id, @Nullable String ifMatch, boolean archived) {
        EntityDefinition entity = gate.allowed(code, EntityDefinition.ARCHIVE);
        long revision = Revisions.required(ifMatch);
        return EntityReads.view(entity, change(() -> writes.archive(entity, id, revision, archived)));
    }

    /**
     * {@code POST /api/v1/entities/{code}/{id}/actions/{action}}: a declared record action (ADR-0032, 6.7) or a
     * transition of the entity's process (ADR-0032, 9.2).
     */
    public EntityRecordView action(
            String code, long id, String action, @Nullable String ifMatch, @Nullable JsonNode body) {
        EntityDefinition entity = gate.allowed(code, action);
        Optional<EntityTransition> transition = EntityProcess.transition(entity, action);
        if (transition.isPresent()) {
            long revision = Revisions.required(ifMatch);
            Map<String, Object> params = EntityRequestReader.params(body);
            return EntityReads.view(
                    entity, change(() -> writes.transition(entity, id, revision, transition.get(), params)));
        }
        EntityActionHandler handler = handler(entity, action);
        long revision = Revisions.required(ifMatch);
        Map<String, Object> params = EntityRequestReader.params(body);
        return EntityReads.view(entity, change(() -> writes.action(entity, id, revision, handler, params)));
    }

    @Override
    public void requireVisible(EntityDefinition entity, long id) {
        read(() -> reads.visible(entity, id, false));
    }

    @Override
    public KeysetPage<?> page(
            EntityDefinition entity, int limit, String cursor, String filter, String sort, String search) {
        return read(() -> reads.page(entity, limit, cursor, filter, sort, search));
    }

    /** A record of a bulk delete: the entity's right was checked for the whole request. */
    @Override
    public void delete(EntityDefinition entity, long id) {
        change(() -> {
            writes.delete(entity, id, null);
            return Map.of();
        });
    }

    /** A record of a bulk archive: the entity's right was checked for the whole request. */
    @Override
    public void archive(EntityDefinition entity, long id) {
        change(() -> writes.archive(entity, id, null, true));
    }

    /**
     * A record of a bulk change, from the revision it has when its turn comes: the entity's right of the action was
     * checked for the whole request; each record goes through the single change's steps (ADR-0032, 6.3).
     */
    @Override
    public void change(EntityDefinition entity, long id, String action, @Nullable JsonNode params) {
        if ("update".equals(action)) {
            change(() -> writes.update(entity, id, EntityReads.revision(reads.visible(entity, id, true)), params));
            return;
        }
        Map<String, Object> body = EntityRequestReader.params(params);
        Optional<EntityTransition> transition = EntityProcess.transition(entity, action);
        if (transition.isPresent()) {
            change(() -> writes.transition(
                    entity, id, EntityReads.revision(reads.visible(entity, id, true)), transition.get(), body));
            return;
        }
        EntityActionHandler handler = handler(entity, action);
        change(() -> writes.action(entity, id, EntityReads.revision(reads.visible(entity, id, true)), handler, body));
    }

    private EntityActionHandler handler(EntityDefinition entity, String action) {
        return registry.handler(entity.code(), action)
                .orElseThrow(() -> ApiException.notFound(
                        ErrorCode.NOT_FOUND, "error.common.entity_action_not_found", Map.of("action", action)));
    }

    private Map<String, Object> change(Supplier<Map<String, Object>> work) {
        return Objects.requireNonNull(changes.execute(status -> work.get()));
    }

    private <T> T read(Supplier<T> work) {
        return Objects.requireNonNull(reading.execute(status -> work.get()));
    }
}
