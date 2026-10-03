package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.bulk.BulkItemScope;
import com.smartup24.cms.instance.common.bulk.BulkRunner;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkRequest;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkResult;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

/**
 * {@code POST /api/v1/entities/{code}/bulk} (roadmap item 56): the bulk actions an entity declares, over up to
 * {@value BulkRunner#MAX_IDS} records, reported record by record ({@link BulkRunner}): {@code delete}, {@code archive}
 * of an archivable entity, {@code update} with the fields of {@code params} and any declared record action with
 * {@code params} as its parameters (ADR-0032, 6.1 and 6.7), each run as the record's single operation, so every record
 * keeps its checks, hooks and audit. The right is the one the entity's action declares; an entity without bulk
 * actions, one the viewer may not see or one of a switched-off module answers the same 404 as {@code form-meta}
 * ({@link EntityGate}).
 */
@RestController
@RequestMapping("/api/v1/entities")
public class EntityBulkController {

    /** The one declared action that is no change of a record. */
    private static final String CREATE = "create";

    private final EntityRegistry registry;
    private final EntityGate gate;
    private final @Nullable BulkItemScope bulkItems;

    /** Over the declarations alone: every module switched on, no item scope. */
    public EntityBulkController(EntityRegistry registry) {
        this(registry, EntityGate.of(registry), null);
    }

    @Autowired
    public EntityBulkController(EntityRegistry registry, EntityGate gate, @Nullable BulkItemScope bulkItems) {
        this.registry = registry;
        this.gate = gate;
        this.bulkItems = bulkItems;
    }

    /** Anyone signed in may ask; the entity's own right is checked below. */
    @Operation(
            summary = "Run a bulk action on records",
            description =
                    "Applies a bulk action of an entity to the selected records; the entity's own right is checked.")
    @PostMapping("/{code}/bulk")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<BulkResult> bulk(@PathVariable String code, @RequestBody BulkRequest body) {
        EntityDefinition entity = gate.findVisible(code)
                .filter(found -> found.capabilities().contains(EntityCapability.BULK))
                .orElseThrow(EntityGate::unknownEntity);
        List<Long> ids = BulkRunner.checkedIds(body);
        String action = body.action() == null ? "" : body.action();
        if (CREATE.equals(action)) {
            throw BulkRunner.unknownAction(action);
        }
        EntityDefinition.EntityAction declared =
                entity.action(action).orElseThrow(() -> BulkRunner.unknownAction(action));
        if (!SecurityContext.hasPermission(entity.form(), declared.permission())) {
            throw ApiException.permissionDenied(entity.form(), declared.permission());
        }
        EntityRecords records = registry.records(code).orElseThrow();
        return ResponseEntity.ok(BulkRunner.run(action, ids, operation(records, action, body.params()), bulkItems));
    }

    /**
     * What a bulk action does to one record, as its single operation does: delete, archive (ADR-0032, 5.4), the
     * fields of {@code params} changed ({@code update}) or a declared record action with {@code params} (ADR-0032,
     * 6.7) — each record from the revision it has when its turn comes.
     */
    private static LongConsumer operation(EntityRecords records, String action, @Nullable JsonNode params) {
        return switch (action) {
            case EntityDefinition.DELETE -> records::delete;
            case EntityDefinition.ARCHIVE -> records::archive;
            default -> id -> records.change(id, action, params == null ? NullNode.getInstance() : params);
        };
    }
}
