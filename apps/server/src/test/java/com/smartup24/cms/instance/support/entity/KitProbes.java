package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.markdown;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.searchable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.ms.note.repository.MsNoteRepository;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.ms.note.service.MsNoteService;
import com.smartup24.cms.instance.ms.note.service.MsNoteService.NoteView;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints of {@link EntityContractTestKitSelfTest}, for its context only: reads of notes broken on purpose, and an
 * entity over the note table whose text needs a right and whose colour needs another to change, served by a controller
 * that keeps those rights — or, on its leaky read, does not.
 */
@TestConfiguration
class KitProbes {

    static final String PATH = "/api/v1/test/kit-probes";
    static final String SECRET_CODE = "ms.kitsecretnotes";
    static final String FIELD_FORM = "md.modules";
    static final String FIELD_ACTION = "view";

    static final EntityDefinition SECRET = Entity.define(SECRET_CODE, "notes")
            .table("ms_notes", "n")
            .scope(EntityScope.owner("created_by"))
            .field(text("title", "notes.col.title")
                    .column("title")
                    .required()
                    .length(1, 255)
                    .list(sortable().searchable()))
            .field(markdown("contentMd", "notes.col.content")
                    .column("content_md")
                    .length(null, MsNoteEntity.MAX_CONTENT)
                    .requires(FIELD_FORM, FIELD_ACTION)
                    .list(searchable()))
            .field(select("color", "notes.col.color", MsNoteEntity.COLORS, "notes.color_")
                    .column("color")
                    .readonlyUnless(FIELD_FORM, FIELD_ACTION))
            .field(instant("modifiedAt", "notes.col.modified_at")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable()))
            .section("main", "entity.section.main", "title", "contentMd")
            .section("settings", "entity.section.settings", "color")
            .actions("create", "update", "delete")
            .defaultSort("modifiedAt", Entity.Sort.DESC)
            .build();

    @Bean
    EntityDefinition kitSecretNotesEntity() {
        return SECRET;
    }

    /** Registered as a member of this configuration. */
    @RestController
    @RequestMapping(PATH)
    static class Probes {

        private final MsNoteService notes;
        private final MsNoteRepository repository;

        Probes(MsNoteService notes, MsNoteRepository repository) {
            this.notes = notes;
            this.repository = repository;
        }

        /** Broken: reads any note, whoever owns it. */
        @GetMapping("/leaky-notes/{id}")
        @RequiresPermission(form = "notes", action = "view")
        public NoteView leakyNote(@PathVariable long id) {
            return repository.findById(id).map(NoteView::from).orElseThrow(() -> missing(id));
        }

        /** Broken: tells another person's note from a missing one. */
        @GetMapping("/forbidding-notes/{id}")
        @RequiresPermission(form = "notes", action = "view")
        public NoteView forbiddingNote(@PathVariable long id) {
            var note = repository.findById(id).orElseThrow(() -> missing(id));
            if (!Objects.equals(note.createdBy(), me())) {
                throw ApiException.permissionDenied("notes", "view");
            }
            return notes.getNote(id, me());
        }

        @GetMapping("/secret-notes")
        @RequiresPermission(form = "notes", action = "view")
        public KeysetPage<Map<String, Object>> secretNotes(
                @RequestParam(required = false) Integer limit,
                @RequestParam(required = false) String cursor,
                @RequestParam(required = false) String filter) {
            return notes.getNotes(me(), limit, cursor, filter, null, null).map(Probes::projected);
        }

        @GetMapping("/secret-notes/{id}")
        @RequiresPermission(form = "notes", action = "view")
        public ResponseEntity<Map<String, Object>> secretNote(@PathVariable long id) {
            NoteView note = notes.getNote(id, me());
            return ResponseEntity.ok().eTag(Revisions.etag(note.revision())).body(projected(note));
        }

        /** Broken: the record with the field its viewer may not see. */
        @GetMapping("/secret-notes/{id}/leaky")
        @RequiresPermission(form = "notes", action = "view")
        public ResponseEntity<Map<String, Object>> leakySecretNote(@PathVariable long id) {
            NoteView note = notes.getNote(id, me());
            return ResponseEntity.ok().eTag(Revisions.etag(note.revision())).body(record(note));
        }

        @PostMapping("/secret-notes")
        @RequiresPermission(form = "notes", action = "create")
        @ResponseStatus(HttpStatus.CREATED)
        public ResponseEntity<Map<String, Object>> createSecretNote(@RequestBody Map<String, Object> body) {
            EntityFieldRights.checkWrite(SECRET, declared(body), null);
            NoteView note = notes.createNote(
                    (String) body.get("title"),
                    (String) body.get("contentMd"),
                    (String) body.get("color"),
                    false,
                    null,
                    me());
            return ResponseEntity.created(URI.create(PATH + "/secret-notes/" + note.id()))
                    .eTag(Revisions.etag(note.revision()))
                    .body(projected(note));
        }

        @PutMapping("/secret-notes/{id}")
        @RequiresPermission(form = "notes", action = "update")
        public ResponseEntity<Map<String, Object>> updateSecretNote(
                @PathVariable long id,
                @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
                @RequestBody Map<String, Object> body) {
            NoteView current = notes.getNote(id, me());
            EntityFieldRights.checkWrite(SECRET, declared(body), record(current));
            NoteView note = notes.updateNote(
                    id,
                    (String) body.get("title"),
                    (String) body.get("contentMd"),
                    (String) body.get("color"),
                    null,
                    null,
                    me(),
                    Revisions.required(ifMatch));
            return ResponseEntity.ok().eTag(Revisions.etag(note.revision())).body(projected(note));
        }

        @DeleteMapping("/secret-notes/{id}")
        @RequiresPermission(form = "notes", action = "delete")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public ResponseEntity<Void> deleteSecretNote(
                @PathVariable long id, @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch) {
            notes.deleteNote(id, me(), Revisions.optional(ifMatch));
            return ResponseEntity.noContent().build();
        }

        private static Map<String, Object> declared(Map<String, Object> body) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String key : new String[] {"title", "contentMd", "color"}) {
                if (body.containsKey(key)) values.put(key, body.get(key));
            }
            return values;
        }

        private static Map<String, Object> projected(NoteView note) {
            return EntityFieldRights.project(SECRET, record(note));
        }

        private static Map<String, Object> record(NoteView note) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", note.id());
            record.put("revision", note.revision());
            record.put("title", note.title());
            record.put("contentMd", note.contentMd());
            record.put("color", note.color());
            return record;
        }

        private static long me() {
            return Objects.requireNonNull(SecurityContext.getCurrentUserId());
        }

        private static ApiException missing(long id) {
            return ApiException.notFound(ErrorCode.NOT_FOUND, "error.note.not_found", Map.of("id", id));
        }
    }
}
