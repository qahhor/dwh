package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.web.AnswersRevision;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.ms.task.api.CreateStatusRequest;
import com.smartup24.cms.instance.ms.task.api.CreateTypeRequest;
import com.smartup24.cms.instance.ms.task.api.TaskStatusView;
import com.smartup24.cms.instance.ms.task.api.TaskTypeView;
import com.smartup24.cms.instance.ms.task.api.UpdateStatusRequest;
import com.smartup24.cms.instance.ms.task.api.UpdateTypeRequest;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskStatusViewService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The task status and type dictionaries, under the same paths as the tasks. */
@RestController
@RequestMapping("/api/v1/tasks")
public class MsTaskStatusController {

    private final MsTaskStatusViewService statuses;

    public MsTaskStatusController(MsTaskStatusViewService statuses) {
        this.statuses = statuses;
    }

    @Operation(summary = "List task statuses", description = "The task statuses in their order.")
    @GetMapping("/statuses")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskStatusView>> listStatuses() {
        return ResponseEntity.ok(statuses.listStatuses());
    }

    @Operation(summary = "Create a task status", description = "Adds a task status.")
    @PostMapping("/statuses")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<TaskStatusView> createStatus(@Valid @RequestBody CreateStatusRequest body) {
        var status = statuses.createStatus(body.pcode(), body.name(), body.color(), body.orderNo(), body.isTerminal());
        return Created.at("/api/v1/tasks/statuses/{id}", status.id(), status);
    }

    @Operation(
            summary = "Update a task status",
            description = "Changes a task status; names the revision it was read at.")
    @PatchMapping("/statuses/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> updateStatus(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateStatusRequest body) {
        long revision = statuses.updateStatusRecord(
                id, body.name(), body.color(), body.orderNo(), body.isTerminal(), Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @Operation(summary = "Delete a task status", description = "Removes a task status.")
    @DeleteMapping("/statuses/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteStatus(@PathVariable("id") Long id) {
        statuses.deleteStatus(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reorder task statuses", description = "Sets the order of the task statuses.")
    @PostMapping("/statuses/reorder")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> reorderStatuses(@RequestBody List<Long> orderedIds) {
        statuses.reorderStatuses(orderedIds);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List task types", description = "The task types in their order.")
    @GetMapping("/types")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskTypeView>> listTypes() {
        return ResponseEntity.ok(statuses.listTypes());
    }

    @Operation(summary = "Create a task type", description = "Adds a task type.")
    @PostMapping("/types")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<TaskTypeView> createType(@Valid @RequestBody CreateTypeRequest body) {
        var type = statuses.createType(body.code(), body.name(), body.icon(), body.color(), body.orderNo());
        return Created.at("/api/v1/tasks/types/{id}", type.id(), type);
    }

    @Operation(summary = "Update a task type", description = "Changes a task type; names the revision it was read at.")
    @PatchMapping("/types/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @AnswersRevision
    public ResponseEntity<Void> updateType(
            @PathVariable("id") Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateTypeRequest body) {
        long revision = statuses.updateType(
                id, body.name(), body.icon(), body.color(), body.orderNo(), Revisions.required(ifMatch));
        return ResponseEntity.noContent().eTag(Revisions.etag(revision)).build();
    }

    @Operation(summary = "Delete a task type", description = "Removes a task type.")
    @DeleteMapping("/types/{id}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteType(@PathVariable("id") Long id) {
        statuses.deleteType(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reorder task types", description = "Sets the order of the task types.")
    @PostMapping("/types/reorder")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> reorderTypes(@RequestBody List<Long> orderedIds) {
        statuses.reorderTypes(orderedIds);
        return ResponseEntity.noContent().build();
    }
}
