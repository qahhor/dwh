package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.ms.task.api.CreateStatusRequest;
import com.smartup24.cms.instance.ms.task.api.CreateTypeRequest;
import com.smartup24.cms.instance.ms.task.api.TaskStatusView;
import com.smartup24.cms.instance.ms.task.api.TaskTypeView;
import com.smartup24.cms.instance.ms.task.api.UpdateStatusRequest;
import com.smartup24.cms.instance.ms.task.api.UpdateTypeRequest;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskStatusViewService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The task status and type dictionaries, under the same paths as the tasks. */
@RestController
@RequestMapping({"/api/v1/tasks/items", "/api/v1/tasks"})
public class MsTaskStatusController {

    private final MsTaskStatusViewService statuses;

    public MsTaskStatusController(MsTaskStatusViewService statuses) {
        this.statuses = statuses;
    }

    @GetMapping("/statuses")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskStatusView>> listStatuses() {
        return ResponseEntity.ok(statuses.listStatuses());
    }

    @PostMapping("/statuses")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    public ResponseEntity<TaskStatusView> createStatus(@Valid @RequestBody CreateStatusRequest body) {
        var status = statuses.createStatus(body.pcode(), body.name(), body.color(), body.orderNo(), body.isTerminal());
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    @PatchMapping("/statuses/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> updateStatus(@PathVariable("id") Long id, @RequestBody UpdateStatusRequest body) {
        statuses.updateStatusRecord(id, body.name(), body.color(), body.orderNo(), body.isTerminal());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/statuses/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> deleteStatus(@PathVariable("id") Long id) {
        statuses.deleteStatus(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/statuses/reorder")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> reorderStatuses(@RequestBody List<Long> orderedIds) {
        statuses.reorderStatuses(orderedIds);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/types")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskTypeView>> listTypes() {
        return ResponseEntity.ok(statuses.listTypes());
    }

    @PostMapping("/types")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    public ResponseEntity<TaskTypeView> createType(@Valid @RequestBody CreateTypeRequest body) {
        var type = statuses.createType(body.code(), body.name(), body.icon(), body.color(), body.orderNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(type);
    }

    @PatchMapping("/types/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> updateType(@PathVariable("id") Long id, @RequestBody UpdateTypeRequest body) {
        statuses.updateType(id, body.name(), body.icon(), body.color(), body.orderNo());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/types/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> deleteType(@PathVariable("id") Long id) {
        statuses.deleteType(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/types/reorder")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    public ResponseEntity<Void> reorderTypes(@RequestBody List<Long> orderedIds) {
        statuses.reorderTypes(orderedIds);
        return ResponseEntity.noContent().build();
    }
}
