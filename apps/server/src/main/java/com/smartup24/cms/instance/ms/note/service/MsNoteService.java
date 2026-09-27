package com.smartup24.cms.instance.ms.note.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.ms.note.repository.MsNoteRepository;
import com.smartup24.cms.instance.ms.note.repository.MsNoteRepository.NoteRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MsNoteService {

    private final MsNoteRepository noteRepository;
    private final AuditLogService auditLogService;
    private final MdCustomFieldService customFieldService;
    private final ModuleRegistryService moduleRegistryService;

    @Autowired
    public MsNoteService(MsNoteRepository noteRepository,
                         AuditLogService auditLogService,
                         MdCustomFieldService customFieldService,
                         @Autowired(required = false) ModuleRegistryService moduleRegistryService) {
        this.noteRepository = noteRepository;
        this.auditLogService = auditLogService;
        this.customFieldService = customFieldService;
        this.moduleRegistryService = moduleRegistryService;
    }

    /** The registry adds the note custom fields to the list (ADR-0019, 2.3); without it, the declared fields only. */
    @Autowired(required = false)
    public void setQueryListRegistry(QueryListRegistry registry) {
        this.registry = registry;
    }

    private QueryListRegistry registry;

    public MsNoteService(MsNoteRepository noteRepository, AuditLogService auditLogService) {
        this(noteRepository, auditLogService, null, null);
    }

    private void checkModuleActive() {
        if (moduleRegistryService != null && !moduleRegistryService.isModuleActive("notes")) {
            throw ApiException.badRequest(ErrorCode.BAD_REQUEST, "Модуль 'notes' отключен администратором");
        }
    }

    public record NoteView(
            Long id,
            String title,
            String contentMd,
            String color,
            boolean isPinned,
            Map<String, Object> attributes,
            Long createdBy,
            Instant createdAt,
            Instant modifiedAt
    ) {
        public static NoteView from(NoteRecord r) {
            return new NoteView(
                    r.id(), r.title(), r.contentMd(), r.color(), r.isPinned(),
                    r.attributes(), r.createdBy(), r.createdAt(), r.modifiedAt()
            );
        }
    }

    /** A page of the owner's notes through the registry ({@code ms.notes}): filter, sort and search {@code q}. */
    @Transactional(readOnly = true)
    public KeysetPage<NoteView> getNotes(Long userId, Integer limit, String cursor,
                                                                          String filter, String sort, String search) {
        checkModuleActive();
        var plan = QueryCompiler.compile(
                registry == null ? MsNoteQuery.LIST : registry.resolve(MsNoteQuery.LIST), filter, sort, limit, cursor, search);
        var page = noteRepository.pageByOwner(plan, userId);
        return new KeysetPage<>(page.items().stream().map(NoteView::from).toList(),
                page.nextCursor(), page.hasMore(), page.totalEstimated());
    }

    @Transactional(readOnly = true)
    public NoteView getNote(Long id, Long userId) {
        checkModuleActive();
        var note = noteRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Заметка не найдена: " + id));
        if (!note.createdBy().equals(userId)) {
            throw ApiException.forbidden(ErrorCode.FORBIDDEN, "Нет доступа к чужой заметке");
        }
        return NoteView.from(note);
    }

    @Transactional
    public NoteView createNote(String title, String contentMd, String color, boolean isPinned,
                               Map<String, Object> attributes, Long userId) {
        checkModuleActive();
        EntityValidator.check(MsNoteEntity.DEFINITION, values(title, contentMd, color, isPinned), false);

        if (customFieldService != null && attributes != null && !attributes.isEmpty()) {
            customFieldService.validateAttributes("NOTE", attributes);
        }

        var note = noteRepository.create(title.trim(), contentMd, color, isPinned, attributes, userId);

        auditLogService.logChange("ms_notes", String.valueOf(note.id()), "I",
                List.of("title", "color", "is_pinned"),
                null,
                Map.of("title", note.title(), "color", note.color(), "is_pinned", note.isPinned()));

        return NoteView.from(note);
    }

    @Transactional
    public NoteView updateNote(Long id, String title, String contentMd, String color, Boolean isPinned,
                               Map<String, Object> attributes, Long userId) {
        checkModuleActive();
        var existing = noteRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Заметка не найдена: " + id));
        if (!existing.createdBy().equals(userId)) {
            throw ApiException.forbidden(ErrorCode.FORBIDDEN, "Нет доступа к чужой заметке");
        }
        EntityValidator.check(MsNoteEntity.DEFINITION, values(title, contentMd, color, isPinned), true);

        if (customFieldService != null && attributes != null && !attributes.isEmpty()) {
            customFieldService.validateAttributes("NOTE", attributes);
        }

        var updated = noteRepository.update(id, title, contentMd, color, isPinned, attributes, userId);

        auditLogService.logChange("ms_notes", String.valueOf(id), "U",
                List.of("title", "color", "is_pinned"),
                Map.of("title", existing.title(), "color", existing.color(), "is_pinned", existing.isPinned()),
                Map.of("title", updated.title(), "color", updated.color(), "is_pinned", updated.isPinned()));

        return NoteView.from(updated);
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

    @Transactional
    public NoteView togglePinned(Long id, Long userId) {
        checkModuleActive();
        var existing = noteRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Заметка не найдена: " + id));
        if (!existing.createdBy().equals(userId)) {
            throw ApiException.forbidden(ErrorCode.FORBIDDEN, "Нет доступа к чужой заметке");
        }

        return updateNote(id, null, null, null, !existing.isPinned(), null, userId);
    }

    @Transactional
    public void deleteNote(Long id, Long userId) {
        checkModuleActive();
        var existing = noteRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Заметка не найдена: " + id));
        if (!existing.createdBy().equals(userId)) {
            throw ApiException.forbidden(ErrorCode.FORBIDDEN, "Нет доступа к чужой заметке");
        }

        noteRepository.delete(id);

        auditLogService.logChange("ms_notes", String.valueOf(id), "D",
                List.of("title"),
                Map.of("title", existing.title()),
                null);
    }
}
