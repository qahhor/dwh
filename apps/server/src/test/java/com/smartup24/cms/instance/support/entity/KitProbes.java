package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.common.entity.field.EntityFields.instant;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.markdown;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.searchable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.select;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.sortable;
import static com.smartup24.cms.instance.common.entity.field.EntityFields.text;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.entity.Entity;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityScope;
import com.smartup24.cms.instance.common.entity.field.FieldSource.SystemColumn;
import com.smartup24.cms.instance.common.entity.runtime.EntityGate;
import com.smartup24.cms.instance.common.entity.runtime.EntityReads;
import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints of {@link EntityContractTestKitSelfTest}, for its context only: reads of records broken on purpose next to
 * the general runtime, and an entity over the note table whose text needs a right and whose colour needs another to
 * change — the runtime serves it as any entity, so its field rights are kept, and a leaky read here does not keep them.
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

        private final EntityGate gate;
        private final EntityReads reads;

        Probes(EntityGate gate, EntityReads reads) {
            this.gate = gate;
            this.reads = reads;
        }

        /** Broken: reads any note, whoever owns it. */
        @GetMapping("/leaky-notes/{id}")
        @RequiresPermission(form = "md.profile", action = "view")
        public ResponseEntity<EntityRecordView> leakyNote(@PathVariable long id) {
            EntityDefinition notes = gate.viewable(MsNoteEntity.CODE);
            return answer(notes, reads.unscoped(notes, id).orElseThrow(Probes::missing));
        }

        /** Broken: tells another person's note from a missing one. */
        @GetMapping("/forbidding-notes/{id}")
        @RequiresPermission(form = "md.profile", action = "view")
        public ResponseEntity<EntityRecordView> forbiddingNote(@PathVariable long id) {
            EntityDefinition notes = gate.viewable(MsNoteEntity.CODE);
            Map<String, Object> note = reads.unscoped(notes, id).orElseThrow(Probes::missing);
            if (((Number) Objects.requireNonNull(note.get("createdBy"))).longValue() != EntityReads.userId()) {
                throw ApiException.permissionDenied("notes", "view");
            }
            return answer(notes, note);
        }

        /** Broken: the record with the field its viewer may not see. */
        @GetMapping("/secret-notes/{id}/leaky")
        @RequiresPermission(form = "md.profile", action = "view")
        public ResponseEntity<EntityRecordView> leakySecretNote(@PathVariable long id) {
            EntityDefinition secret = gate.viewable(SECRET_CODE);
            return answer(secret, reads.visible(secret, id, false));
        }

        /** The record as it is, every field included: no projection by the viewer's rights. */
        private static ResponseEntity<EntityRecordView> answer(EntityDefinition entity, Map<String, Object> record) {
            EntityRecordView view = new EntityRecordView(record, EntityReads.actions(entity));
            return ResponseEntity.ok().eTag(Revisions.etag(view.revision())).body(view);
        }

        private static ApiException missing() {
            return ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found");
        }
    }
}
