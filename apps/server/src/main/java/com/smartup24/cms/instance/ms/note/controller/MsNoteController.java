package com.smartup24.cms.instance.ms.note.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.ms.note.service.MsNoteService;
import com.smartup24.cms.instance.ms.note.service.MsNoteService.NoteView;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notes")
public class MsNoteController {

    private final MsNoteService noteService;

    public MsNoteController(MsNoteService noteService) {
        this.noteService = noteService;
    }

    public record CreateNoteRequest(
            String title, String contentMd, String color, boolean isPinned, Map<String, Object> attributes) {}

    public record UpdateNoteRequest(
            String title, String contentMd, String color, Boolean isPinned, Map<String, Object> attributes) {}

    @GetMapping
    @RequiresPermission(form = "notes", action = "view")
    public ResponseEntity<KeysetPage<NoteView>> getNotes(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        // Registry list ms.notes (ADR-0016): pages instead of the whole list, the owner's notes only.
        return ResponseEntity.ok(noteService.getNotes(userId, limit, cursor, filter, sort, q));
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = "notes", action = "view")
    public ResponseEntity<NoteView> getNote(@PathVariable Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        return ResponseEntity.ok(noteService.getNote(id, userId));
    }

    @PostMapping
    @RequiresPermission(form = "notes", action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<NoteView> createNote(@RequestBody CreateNoteRequest body) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        NoteView note = noteService.createNote(
                body.title(), body.contentMd(), body.color(), body.isPinned(), body.attributes(), userId);
        return Created.at("/api/v1/notes/{id}", note.id(), note);
    }

    @PutMapping("/{id}")
    @RequiresPermission(form = "notes", action = "update")
    public ResponseEntity<NoteView> updateNote(
            @PathVariable Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateNoteRequest body) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        return ResponseEntity.ok(noteService.updateNote(
                id,
                body.title(),
                body.contentMd(),
                body.color(),
                body.isPinned(),
                body.attributes(),
                userId,
                Revisions.required(ifMatch)));
    }

    public record PinRequest(boolean pinned) {}

    /** Pins or unpins the note (plan item 3.4): the same call twice leaves the same note. */
    @PutMapping("/{id}/pin")
    @RequiresPermission(form = "notes", action = "update")
    public ResponseEntity<NoteView> setPin(@PathVariable Long id, @RequestBody PinRequest body) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        return ResponseEntity.ok(noteService.setPinned(id, userId, body.pinned()));
    }

    /** Flips the pin: deprecated for PUT /{id}/pin, answers until its sunset (ADR-0023). */
    @PostMapping("/{id}/pin")
    @RequiresPermission(form = "notes", action = "update")
    public ResponseEntity<NoteView> togglePin(@PathVariable Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        return ResponseEntity.ok(noteService.togglePinned(id, userId));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = "notes", action = "delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteNote(@PathVariable Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        noteService.deleteNote(id, userId);
        return ResponseEntity.noContent().build();
    }
}
