package com.smartup24.cms.instance.ms.note.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.ms.note.service.MsNoteService;
import com.smartup24.cms.instance.ms.note.service.MsNoteService.NoteView;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(summary = "List my notes", description = "The caller's notes.")
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

    @Operation(summary = "Get a note", description = "One note of the caller.")
    @GetMapping("/{id}")
    @RequiresPermission(form = "notes", action = "view")
    public ResponseEntity<NoteView> getNote(@PathVariable Long id) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        return ResponseEntity.ok(noteService.getNote(id, userId));
    }

    @Operation(
            summary = "Create a note",
            description = "Adds a note with its title, Markdown text, colour and custom field values.")
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

    @Operation(
            summary = "Update a note",
            description = "Replaces a note of the caller; names the revision it was read at.")
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
    @Operation(
            summary = "Pin or unpin a note",
            description = "Sets whether a note is pinned; the same call twice leaves the same note.")
    @PutMapping("/{id}/pin")
    @RequiresPermission(form = "notes", action = "update")
    public ResponseEntity<NoteView> setPin(@PathVariable Long id, @RequestBody PinRequest body) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        return ResponseEntity.ok(noteService.setPinned(id, userId, body.pinned()));
    }

    public record ArchivedRequest(boolean archived) {}

    /**
     * Archives or restores the note (ADR-0032, 5.4): it leaves the list and the search but still reads by id. The
     * right is the note right's {@code delete} — the default of ADR-0032, 19 (question 11), an assumption until the
     * product owner answers it.
     */
    @Operation(
            summary = "Archive or restore a note",
            description = "Moves a note of the caller to the archive or back; names the revision it was read at.")
    @PutMapping("/{id}/archived")
    @RequiresPermission(form = "notes", action = "delete")
    public ResponseEntity<NoteView> setArchived(
            @PathVariable Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody ArchivedRequest body) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        return ResponseEntity.ok(noteService.setArchived(id, userId, body.archived(), Revisions.required(ifMatch)));
    }

    @Operation(
            summary = "Delete a note",
            description = "Removes a note of the caller; with If-Match, only from the revision it names.")
    @DeleteMapping("/{id}")
    @RequiresPermission(form = "notes", action = "delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteNote(
            @PathVariable Long id, @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch) {
        Long userId = SecurityContext.getCurrentUserId();
        if (userId == null) throw ApiException.unauthorized("error.note.not_authenticated");
        noteService.deleteNote(id, userId, Revisions.optional(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
