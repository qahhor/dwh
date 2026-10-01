package com.smartup24.cms.instance.ms.task.controller;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.task.api.AttachFileRequest;
import com.smartup24.cms.instance.ms.task.api.TaskFileView;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.service.MsTaskFileService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Files attached to a task, under the same paths as the tasks. */
@RestController
@RequestMapping("/api/v1/tasks")
public class MsTaskFileController {

    private final MsTaskFileService files;

    public MsTaskFileController(MsTaskFileService files) {
        this.files = files;
    }

    @Operation(summary = "List task files", description = "The files attached to a task.")
    @GetMapping("/{id}/files")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "view")
    public ResponseEntity<List<TaskFileView>> getTaskFiles(@PathVariable("id") Long id) {
        return ResponseEntity.ok(files.listTaskFiles(id, SecurityContext.getCurrentUserId()));
    }

    @Operation(summary = "Attach a file to a task", description = "Attaches a stored file to a task.")
    @PostMapping("/{id}/files")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> attachFile(@PathVariable("id") Long id, @RequestBody AttachFileRequest body) {
        files.attachFile(id, body.fileId(), SecurityContext.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Detach a file from a task", description = "Removes the link between a task and a file.")
    @DeleteMapping("/{id}/files/{fileId}")
    @RequiresPermission(form = MsTaskPref.FORM_TASKS, action = "update")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> detachFile(@PathVariable("id") Long id, @PathVariable("fileId") UUID fileId) {
        files.detachFile(id, fileId, SecurityContext.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
