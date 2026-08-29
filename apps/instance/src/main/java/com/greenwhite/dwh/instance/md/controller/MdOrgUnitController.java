package com.greenwhite.dwh.instance.md.controller;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import com.greenwhite.dwh.instance.md.service.MdOrgUnitService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public ResponseEntity<List<MdOrgUnitRepository.OrgUnitRecord>> list() {
        return ResponseEntity.ok(orgUnitService.listAll());
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "view")
    public ResponseEntity<MdOrgUnitRepository.OrgUnitRecord> getById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(orgUnitService.getById(id));
    }

    @PostMapping
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "create")
    public ResponseEntity<MdOrgUnitRepository.OrgUnitRecord> create(@Valid @RequestBody CreateOrgUnitDto body) {
        var unit = orgUnitService.create(body.parentId(), body.code(), body.name(), body.kind(), body.orderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(unit);
    }

    @PatchMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "update")
    public ResponseEntity<Void> update(@PathVariable("id") Long id, @RequestBody UpdateOrgUnitDto body) {
        orgUnitService.update(id, body.parentId(), body.name(), body.kind(), body.state(), body.orderNo());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "delete")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        orgUnitService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Позиция сотрудника в дереве — полная замена набора узлов. */
    @PutMapping("/users/{userId}")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "assign")
    public ResponseEntity<Void> assignUser(@PathVariable("userId") Long userId,
                                           @RequestBody AssignUnitsDto body) {
        scopeService.assignUserOrgUnits(userId, body.orgUnitIds());
        return ResponseEntity.noContent().build();
    }

    /** Правило видимости у роли: ALL, SUBTREE, UNITS или SELF. */
    @PutMapping("/roles/{roleId}/rule")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "assign")
    public ResponseEntity<Void> setRoleRule(@PathVariable("roleId") Long roleId,
                                            @Valid @RequestBody ScopeRuleDto body) {
        scopeService.setRoleRule(roleId, body.rule());
        return ResponseEntity.noContent().build();
    }

    /** Скоуп сотрудника глазами администратора: какое правило и какие узлы видны. */
    @GetMapping("/users/{userId}/scope")
    @RequiresPermission(form = MdPref.FORM_ORG_UNITS, action = "view")
    public ResponseEntity<MdScopeService.UserScope> getUserScope(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(scopeService.getUserScope(userId));
    }

    public record CreateOrgUnitDto(
            Long parentId,
            @NotBlank String code,
            @NotBlank String name,
            String kind,
            int orderNo
    ) {}

    public record UpdateOrgUnitDto(
            Long parentId,
            String name,
            String kind,
            String state,
            Integer orderNo
    ) {}

    public record AssignUnitsDto(List<Long> orgUnitIds) {}

    public record ScopeRuleDto(@NotBlank String rule) {}
}
