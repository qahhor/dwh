package com.smartup24.cms.instance.common.entity.runtime;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import io.swagger.v3.oas.annotations.Hidden;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/entities/{code}} (ADR-0032, 6.1; plan 10/10, item 5.4): the records of every entity declared with a
 * table — list, read, create, change, delete, archive and the declared record actions. Every handler is open to
 * whoever is signed in; the runtime checks the entity's own rights ({@link EntityGate}), as {@code form-meta} does. The
 * code of an entity has a dot, so the literal paths {@code menu} and {@code bulk} never meet it; an id is a number.
 * The API description names each entity's own paths and schemas instead of this template ({@code
 * EntityOpenApiCustomizer}).
 */
@Hidden
@RestController
@RequestMapping("/api/v1/entities")
public class EntityController {

    /** An entity code: dotted lower-case segments ({@code ms.notes}). */
    static final String CODE = "{code:[a-z][a-z0-9_]*\\.[a-z0-9_.]+}";

    private static final String RECORD = "/" + CODE + "/{id:\\d+}";

    private final EntityRuntime runtime;

    public EntityController(EntityRuntime runtime) {
        this.runtime = runtime;
    }

    public record ArchivedRequest(boolean archived) {}

    @GetMapping("/" + CODE)
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<KeysetPage<EntityRecordView>> list(
            @PathVariable String code,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String filter,
            @RequestParam(required = false) @Nullable String sort) {
        return ResponseEntity.ok(runtime.list(code, limit, cursor, filter, sort, q));
    }

    @GetMapping(RECORD)
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<EntityRecordView> get(@PathVariable String code, @PathVariable long id) {
        return ResponseEntity.ok(runtime.get(code, id));
    }

    @PostMapping("/" + CODE)
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<EntityRecordView> create(
            @PathVariable String code, @RequestBody(required = false) @Nullable JsonNode body) {
        EntityRecordView record = runtime.create(code, body);
        return Created.at("/api/v1/entities/{code}/{id}", new Object[] {code, record.id()}, record);
    }

    @PatchMapping(RECORD)
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<EntityRecordView> update(
            @PathVariable String code,
            @PathVariable long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody(required = false) @Nullable JsonNode body) {
        return ResponseEntity.ok(runtime.update(code, id, ifMatch, body));
    }

    @DeleteMapping(RECORD)
    @RequiresPermission(form = "md.profile", action = "view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(
            @PathVariable String code,
            @PathVariable long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch) {
        runtime.delete(code, id, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(RECORD + "/archived")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<EntityRecordView> archive(
            @PathVariable String code,
            @PathVariable long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody ArchivedRequest body) {
        return ResponseEntity.ok(runtime.archive(code, id, ifMatch, body.archived()));
    }

    @PostMapping(RECORD + "/actions/{action:[a-z][a-z0-9_]*}")
    @RequiresPermission(form = "md.profile", action = "view")
    public ResponseEntity<EntityRecordView> action(
            @PathVariable String code,
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody(required = false) @Nullable JsonNode body) {
        return ResponseEntity.ok(runtime.action(code, id, action, ifMatch, body));
    }
}
