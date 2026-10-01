package com.smartup24.cms.instance.md.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos.AssignUnitsDto;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos.CreateOrgUnitDto;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos.OrgUnitView;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos.ScopeRuleDto;
import com.smartup24.cms.instance.md.api.MdOrgUnitDtos.UpdateOrgUnitDto;
import com.smartup24.cms.instance.md.pref.MdPref;
import com.smartup24.cms.instance.md.service.MdOrgUnitService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Оргструктура и скоуп данных (ADR-0013).
 *
 * Права на всю форму — только у администратора: смена правила видимости
 * меняет доступ к данным так же радикально, как выдача права.
 */
@RestController
@RequestMapping("/api/v1/iam/org-units")
public class MdOrgUnitController {

    private final MdOrgUnitService orgUnitService;
    private final MdScopeService scopeService;

    public MdOrgUnitController(MdOrgUnitService orgUnitService, MdScopeService scopeService) {
        this.orgUnitService = orgUnitService;
        this.scopeService = scopeService;
    }

    @GetMapping
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "view")
    public ResponseEntity<List<OrgUnitView>> list() {
        return ResponseEntity.ok(orgUnitService.listAll());
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "view")
    public ResponseEntity<OrgUnitView> getById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(orgUnitService.getById(id));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<OrgUnitView> create(@Valid @RequestBody CreateOrgUnitDto body) {
        var unit = orgUnitService.create(body.parentId(), body.code(), body.name(), body.kind(), body.orderNo());
        return Created.at("/api/v1/iam/org-units/{id}", unit.id(), unit);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> update(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateOrgUnitDto body) {
        long revision = orgUnitService.update(
                id,
                body.parentIdPresent(),
                body.parentId(),
                body.name(),
                body.kind(),
                body.state(),
                body.orderNo(),
                Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        orgUnitService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Явные назначения сотрудника и отдельная legacy-привязка. */
    @GetMapping("/users/{userId}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "view")
    public ResponseEntity<MdOrgUnitDtos.UserAssignments> getUserAssignments(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(scopeService.getUserAssignments(userId));
    }

    /** Явное правило роли; отсутствие строки у существующей роли означает ALL. */
    @GetMapping("/roles/{roleId}/rule")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "view")
    public ResponseEntity<MdOrgUnitDtos.RoleRule> getRoleRule(@PathVariable("roleId") Long roleId) {
        return ResponseEntity.ok(scopeService.getRoleScopeRule(roleId));
    }

    /**
     * Replaces the org units of a user whole, made from the user's revision ({@code If-Match}, ADR-0024); the answer
     * carries the user's new revision in {@code ETag}.
     */
    @PutMapping("/users/{userId}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "assign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> assignUser(
            @PathVariable("userId") Long userId,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody AssignUnitsDto body) {
        long revision = scopeService.assignUserOrgUnits(userId, body.orgUnitIds(), Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    /**
     * Sets the scope rule of a role (ALL, SUBTREE, UNITS or SELF), made from the role's revision ({@code If-Match},
     * ADR-0024); the answer carries the role's new revision in {@code ETag}.
     */
    @PutMapping("/roles/{roleId}/rule")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "assign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> setRoleRule(
            @PathVariable("roleId") Long roleId,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ScopeRuleDto body) {
        long revision = scopeService.setRoleRule(roleId, body.rule(), Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    /** Скоуп сотрудника глазами администратора: какое правило и какие узлы видны. */
    @GetMapping("/users/{userId}/scope")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "view")
    public ResponseEntity<MdScopeService.UserScope> getUserScope(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(scopeService.getUserScope(userId));
    }
}
