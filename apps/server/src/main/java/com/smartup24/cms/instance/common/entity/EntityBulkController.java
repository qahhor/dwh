package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.bulk.BulkRunner;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkRequest;
import com.smartup24.cms.instance.common.bulk.BulkRunner.BulkResult;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code POST /api/v1/entities/{code}/bulk} (roadmap item 56): the bulk actions an entity declares, over up to
 * {@value BulkRunner#MAX_IDS} records, reported record by record ({@link BulkRunner}). Today that is
 * {@code delete}, run through the module's own delete, so each record keeps its checks and audit. The right is
 * the one the entity's action declares; an entity without bulk actions, or one the viewer may not see, answers
 * the same 404 as {@code form-meta}.
 */
@RestController
@RequestMapping("/api/v1/entities")
public class EntityBulkController {

    private final EntityRegistry registry;

    public EntityBulkController(EntityRegistry registry) {
        this.registry = registry;
    }

    /** Anyone signed in may ask; the entity's own right is checked below. */
    @PostMapping("/{code}/bulk")
    @RequiresPermission(form = "iam.profile", action = "view")
    public ResponseEntity<BulkResult> bulk(@PathVariable String code, @RequestBody BulkRequest body) {
        EntityDefinition entity = registry.find(code)
                .filter(found -> found.capabilities().contains(EntityCapability.BULK))
                .filter(found -> SecurityContext.hasPermission(found.form(), "view"))
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "ENTITY_NOT_FOUND"));
        List<Long> ids = BulkRunner.checkedIds(body);
        String action = body.action() == null ? "" : body.action();
        if (!EntityDefinition.DELETE.equals(action)) {
            throw BulkRunner.unknownAction(action);
        }
        EntityDefinition.EntityAction delete = entity.action(action).orElseThrow(() -> BulkRunner.unknownAction(action));
        if (!SecurityContext.hasPermission(entity.form(), delete.permission())) {
            throw ApiException.permissionDenied(entity.form(), delete.permission());
        }
        EntityRecords records = registry.records(code).orElseThrow();
        return ResponseEntity.ok(BulkRunner.run(action, ids, records::delete));
    }
}
