package com.smartup24.cms.instance.ms.note.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityAuditRow;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityFieldRights;
import com.smartup24.cms.instance.common.entity.EntityLists;
import com.smartup24.cms.instance.common.entity.EntityModel;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.web.Revisioned;
import com.smartup24.cms.instance.common.web.Revisions;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.ms.note.repository.MsNoteRepository;
import com.smartup24.cms.instance.ms.note.repository.MsNoteRepository.NoteRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notes by their declaration (ADR-0032; plan 10/10, items 5.1 and 5.3): the list and every read by id take the
 * entity's declared scope ({@link EntityScopes}), a save is checked against the field rights
 * ({@link EntityFieldRights}) and the field rules ({@link EntityValidator}), and a note is archived and restored
 * instead of only deleted (the ARCHIVE capability).
 */
@Service
public class MsNoteService {

    private static final String AUDIT_TABLE = "ms_notes";

    private final MsNoteRepository noteRepository;
    private final AuditLogService auditLogService;
    private final MdCustomFieldService customFieldService;
    private final ModuleRegistryService moduleRegistryService;
    private QueryListRegistry registry;
    private ObjectProvider<EntityRegistry> entities;
    private EntityScopes scopes = EntityScopes.withoutOrgUnits();

    @Autowired
    public MsNoteService(
            MsNoteRepository noteRepository,
            AuditLogService auditLogService,
            MdCustomFieldService customFieldService,
            @Autowired(required = false) ModuleRegistryService moduleRegistryService) {
        this.noteRepository = noteRepository;
        this.auditLogService = auditLogService;
        this.customFieldService = customFieldService;
        this.moduleRegistryService = moduleRegistryService;
    }

    public MsNoteService(MsNoteRepository noteRepository, AuditLogService auditLogService) {
        this(noteRepository, auditLogService, null, null);
    }

    /**
     * The registry gives the note list derived from the declaration with the custom fields (ADR-0019, 2.3; ADR-0032,
     * 3.4); without it, the derived list alone.
     */
    @Autowired(required = false)
    public void setQueryListRegistry(QueryListRegistry registry) {
        this.registry = registry;
    }

    /**
     * The note with its custom fields as they are now, for the audit row (plan 10/10, item 5.0). Read when a note
     * is saved, not at start: the registry is built from the records beans, which need this service.
     */
    @Autowired(required = false)
    public void setEntityRegistry(ObjectProvider<EntityRegistry> entities) {
        this.entities = entities;
    }

    /** The predicates of the declared scope; built by hand, the owner scope of a note needs no md module. */
    @Autowired(required = false)
    public void setEntityScopes(EntityScopes scopes) {
        this.scopes = scopes;
    }

    private void checkModuleActive() {
        if (moduleRegistryService != null && !moduleRegistryService.isModuleActive("notes")) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "error.note.module_disabled");
        }
    }

    /** A note as the API answers it; {@code archived} is true once it is archived (ADR-0032, 5.4). */
    public record NoteView(
            Long id,
            String title,
            String contentMd,
            String color,
            boolean isPinned,
            Map<String, Object> attributes,
            Long createdBy,
            Instant createdAt,
            Instant modifiedAt,
            long revision,
            boolean archived,
            @Nullable Instant archivedAt)
            implements Revisioned {
        public static NoteView from(NoteRecord r) {
            return new NoteView(
                    r.id(),
                    r.title(),
                    r.contentMd(),
                    r.color(),
                    r.isPinned(),
                    r.attributes(),
                    r.createdBy(),
                    r.createdAt(),
                    r.modifiedAt(),
                    r.revision(),
                    r.archivedAt() != null,
                    r.archivedAt());
        }
    }

    /**
     * A page of the owner's notes through the registry ({@code ms.notes}): filter, sort and search {@code q}. Archived
     * notes are left out unless the filter names {@code archived} (ADR-0032, 5.4).
     */
    @Transactional(readOnly = true)
    public KeysetPage<NoteView> getNotes(
            Long userId, Integer limit, String cursor, String filter, String sort, String search) {
        checkModuleActive();
        var plan = QueryCompiler.compile(
                registry == null ? EntityLists.queryList(MsNoteEntity.DEFINITION) : registry.get(MsNoteEntity.CODE),
                filter,
                sort,
                limit,
                cursor,
                search);
        var page = noteRepository.page(plan, scopes.listPredicate(MsNoteEntity.DEFINITION, plan, userId));
        return page.map(NoteView::from);
    }

    /** One note of the owner; an archived one too, so an old reference to it still reads (ADR-0032, 5.4). */
    @Transactional(readOnly = true)
    public NoteView getNote(Long id, Long userId) {
        return NoteView.from(ownNote(id, userId));
    }

    @Transactional
    public NoteView createNote(
            String title,
            String contentMd,
            String color,
            boolean isPinned,
            Map<String, Object> attributes,
            Long userId) {
        checkModuleActive();
        Map<String, Object> values = values(title, contentMd, color, isPinned);
        EntityFieldRights.checkWrite(MsNoteEntity.DEFINITION, values, null);
        EntityValidator.check(MsNoteEntity.DEFINITION, values, false);

        var note =
                noteRepository.create(title.trim(), contentMd, color, isPinned, checkedAttributes(attributes), userId);

        Map<String, Object> row = audited(note);
        auditLogService.logChange(AUDIT_TABLE, String.valueOf(note.id()), "I", List.copyOf(row.keySet()), null, row);

        return NoteView.from(note);
    }

    @Transactional
    public NoteView updateNote(
            Long id,
            String title,
            String contentMd,
            String color,
            Boolean isPinned,
            Map<String, Object> attributes,
            Long userId,
            long expectedRevision) {
        var existing = ownNote(id, userId);
        Map<String, Object> values = values(title, contentMd, color, isPinned);
        EntityFieldRights.checkWrite(
                MsNoteEntity.DEFINITION,
                values,
                values(existing.title(), existing.contentMd(), existing.color(), existing.isPinned()));
        EntityValidator.check(MsNoteEntity.DEFINITION, values, true);

        var updated = noteRepository.update(
                id, title, contentMd, color, isPinned, checkedAttributes(attributes), userId, expectedRevision);

        Map<String, Object> before = audited(existing);
        Map<String, Object> after = audited(updated);
        auditLogService.logChange(
                AUDIT_TABLE, String.valueOf(id), "U", EntityAuditRow.changed(before, after), before, after);

        return NoteView.from(updated);
    }

    private Map<String, Object> checkedAttributes(Map<String, Object> attributes) {
        if (customFieldService != null && attributes != null && !attributes.isEmpty()) {
            return customFieldService.checkedAttributes("NOTE", attributes);
        }
        return attributes;
    }

    /** The declared fields a save carries; an absent one (null) is left out, so an update keeps it. */
    private static Map<String, Object> values(String title, String contentMd, String color, Boolean isPinned) {
        Map<String, Object> values = new HashMap<>();
        if (title != null) values.put("title", title);
        if (contentMd != null) values.put("contentMd", contentMd);
        if (color != null) values.put("color", color);
        if (isPinned != null) values.put("isPinned", isPinned);
        return values;
    }

    /** The audit row of a note: every declared field and every custom field it has (plan 10/10, item 5.0). */
    private Map<String, Object> audited(NoteRecord note) {
        EntityRegistry resolved = entities == null ? null : entities.getIfAvailable();
        EntityDefinition entity =
                resolved == null ? MsNoteEntity.DEFINITION : resolved.resolve(MsNoteEntity.DEFINITION);
        return EntityAuditRow.of(
                entity, values(note.title(), note.contentMd(), note.color(), note.isPinned()), note.attributes());
    }

    /**
     * Sets the pin (PUT /notes/{id}/pin, plan item 3.4): the same call twice leaves the same note. The write happens
     * only when the pin changes (plan item 3.6), so concurrent calls leave one state and one audit row per change.
     */
    @Transactional
    public NoteView setPinned(Long id, Long userId, boolean pinned) {
        var before = ownNote(id, userId);
        noteRepository
                .setPinned(id, pinned, userId)
                .ifPresent(changed -> auditLogService.logChange(
                        AUDIT_TABLE,
                        String.valueOf(id),
                        "U",
                        List.of("isPinned"),
                        Map.of("isPinned", !changed.isPinned()),
                        Map.of("isPinned", changed.isPinned())));
        return NoteView.from(noteRepository.findById(id).orElse(before));
    }

    /**
     * Archives or restores the note (ADR-0032, 5.4): a switch of ADR-0023 — written and audited only when the state
     * changes. With a revision ({@code If-Match}) the change is made only from it: a stale one is 409, while asking for
     * the state the note already has answers it as it is. Without one — the bulk action — it runs as asked.
     */
    @Transactional
    public NoteView setArchived(Long id, Long userId, boolean archived, @Nullable Long expectedRevision) {
        var before = ownNote(id, userId);
        Optional<NoteRecord> changed = noteRepository.setArchived(id, archived, userId, expectedRevision);
        if (changed.isPresent()) {
            auditLogService.logChange(
                    AUDIT_TABLE,
                    String.valueOf(id),
                    "U",
                    List.of(EntityModel.ARCHIVED),
                    Map.of(EntityModel.ARCHIVED, !archived),
                    Map.of(EntityModel.ARCHIVED, archived));
            return NoteView.from(changed.get());
        }
        if (expectedRevision != null && before.revision() != expectedRevision) {
            throw Revisions.conflict();
        }
        return NoteView.from(before);
    }

    /**
     * The owner's note. Another person's note answers exactly as a missing one, 404 (plan 10/10, item 5.0): the
     * declared owner scope is in the query (ADR-0032, 5.1), so the answer does not tell that a note with this id exists.
     */
    private NoteRecord ownNote(Long id, Long userId) {
        checkModuleActive();
        return noteRepository
                .findVisible(id, scopes.rows(MsNoteEntity.DEFINITION, userId))
                .orElseThrow(
                        () -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.note.not_found", Map.of("id", id)));
    }

    @Transactional
    public void deleteNote(Long id, Long userId) {
        deleteNote(id, userId, null);
    }

    /** Deletes the note; with a revision ({@code If-Match}), only from it — a stale one is 409 (ADR-0032, 5.3). */
    @Transactional
    public void deleteNote(Long id, Long userId, @Nullable Long expectedRevision) {
        var existing = ownNote(id, userId);

        if (!noteRepository.delete(id, expectedRevision)) {
            throw Revisions.conflict();
        }

        Map<String, Object> row = audited(existing);
        auditLogService.logChange(AUDIT_TABLE, String.valueOf(id), "D", List.copyOf(row.keySet()), row, null);
    }
}
