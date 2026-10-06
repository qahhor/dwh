package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.store.EntityStoreRepository;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryList;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.ScopeFilter;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * How the runtime reads records (ADR-0032, 6.1–6.2 and 5.1): a record by id only in the viewer's scope — outside it the
 * same 404 as a missing id (ADR-0013) — and the pages of the entity's list with the scope and the archive in the same
 * SQL; every record is answered with the fields the viewer may see ({@link EntityFieldRights#project}) and the actions
 * the viewer's rights and the record's state allow (ADR-0032, 6.2 and 9.2). A record read by id holds the rows of its
 * collections (ADR-0032, 9.1); a page of the list does not.
 */
@Component
public class EntityReads {

    private final QueryListRegistry lists;
    private final EntityScopes scopes;
    private final EntityStoreRepository store;
    private final EntityLines lines;
    private final EntityLabelResolver resolver;

    @Autowired
    public EntityReads(
            QueryListRegistry lists,
            EntityScopes scopes,
            EntityStoreRepository store,
            EntityLines lines,
            EntityLabelResolver resolver) {
        this.lists = lists;
        this.scopes = scopes;
        this.store = store;
        this.lines = lines;
        this.resolver = resolver;
    }

    /** Constructor without an external label resolver, for tests. */
    public EntityReads(QueryListRegistry lists, EntityScopes scopes, EntityStoreRepository store, EntityLines lines) {
        this(lists, scopes, store, lines, new EntityLabelResolver(new EntityRegistry(List.of()), scopes, null));
    }

    /** The entity's list with its custom fields: its select is the projection of every record read. */
    public QueryList list(EntityDefinition entity) {
        return lists.get(Objects.requireNonNull(entity.listCode(), entity.code()));
    }

    /**
     * The record in the viewer's scope, every value of it; with {@code lock} the row stays locked until the end of
     * the transaction (step 4 of ADR-0032, 6.3).
     *
     * @throws ApiException 404 {@code error.common.record_not_found} for a missing record and one outside the scope
     */
    public Map<String, Object> visible(EntityDefinition entity, long id, boolean lock) {
        Map<String, Object> record = store.find(entity, list(entity), id, scopes.rows(entity, userId()), lock)
                .orElseThrow(EntityReads::recordNotFound);
        return lines.withRows(entity, record);
    }

    /**
     * The record whoever looks, without the scope of a viewer: what an outbound event carries (ADR-0032, 6.9), its
     * restricted fields left out by the caller. Empty when the record is gone.
     */
    public Optional<Map<String, Object>> unscoped(EntityDefinition entity, long id) {
        return store.find(entity, list(entity), id, EntityScopes.fragment(ScopeFilter.unrestricted()), false)
                .map(record -> lines.withRows(entity, record));
    }

    /** A page of the entity's list for the viewer, each record as the viewer may read it. */
    public KeysetPage<Map<String, Object>> page(
            EntityDefinition entity,
            @Nullable Integer limit,
            @Nullable String cursor,
            @Nullable String filter,
            @Nullable String sort,
            @Nullable String search) {
        QueryPlan plan = QueryCompiler.compile(list(entity), filter, sort, limit, cursor, search);
        return store.page(entity, plan, scopes.listPredicate(entity, plan, userId()))
                .map(record -> EntityFieldRights.project(entity, record));
    }

    /**
     * The answer of a record: the fields the viewer may see, relation labels resolved in batch and the actions they
     * may take (ADR-0032, 4.6 and 6.2).
     */
    public EntityRecordView view(EntityDefinition entity, Map<String, Object> record) {
        Map<String, Object> projected = EntityFieldRights.project(entity, record);
        Map<String, Object> labels = resolver.resolve(entity, projected, userId());
        return new EntityRecordView(projected, actions(entity, record), labels);
    }

    /**
     * The answers for a page of records: fields projected, relation labels resolved in batch (ADR-0032, 4.6).
     */
    public KeysetPage<EntityRecordView> views(EntityDefinition entity, KeysetPage<Map<String, Object>> page) {
        if (page.items().isEmpty()) {
            return new KeysetPage<>(
                    List.of(), page.nextCursor(), page.hasMore(), page.totalEstimated(), page.totalExact());
        }
        List<Map<String, Object>> items = page.items();
        List<Map<String, Object>> labels = resolver.resolveBatch(entity, items, userId());
        List<EntityRecordView> views = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> record = items.get(i);
            views.add(new EntityRecordView(record, actions(entity, record), labels.get(i)));
        }
        return new KeysetPage<>(views, page.nextCursor(), page.hasMore(), page.totalEstimated(), page.totalExact());
    }

    /** A record view without resolving relation labels. */
    public static EntityRecordView plainView(EntityDefinition entity, Map<String, Object> record) {
        return new EntityRecordView(EntityFieldRights.project(entity, record), actions(entity, record));
    }

    /**
     * The declared actions whose right the viewer holds, in declaration order, that the record's state allows: a
     * transition only from a state it leaves, no change in a terminal state (ADR-0032, 9.2).
     */
    public static List<String> actions(EntityDefinition entity, Map<String, ?> record) {
        return EntityProcess.actions(entity, record);
    }

    /** The id of a record or of a row read. */
    public static long id(Map<String, ?> record) {
        return ((Number) Objects.requireNonNull(record.get(SystemColumn.ID.key()))).longValue();
    }

    /** The revision of a record read. */
    public static long revision(Map<String, Object> record) {
        return ((Number) Objects.requireNonNull(record.get(SystemColumn.REVISION.key()))).longValue();
    }

    /** The signed-in person; the endpoints of the runtime are open only to someone signed in. */
    public static long userId() {
        Long id = SecurityContext.getCurrentUserId();
        if (id == null) throw new ApiException(ErrorCode.UNAUTHORIZED);
        return id;
    }

    static ApiException recordNotFound() {
        return ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found");
    }
}
