package com.smartup24.cms.instance.ms.note.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityRecords;
import com.smartup24.cms.instance.common.security.SecurityContext;
import org.springframework.stereotype.Component;

/**
 * Notes for the platform's history, export and bulk delete (roadmap item 56): a note is its owner's alone, so
 * another person's note reads as missing, and a delete is the owner's single delete, with its audit.
 */
@Component
public class MsNoteRecords implements EntityRecords {

    private final MsNoteService notes;

    public MsNoteRecords(MsNoteService notes) {
        this.notes = notes;
    }

    @Override
    public String entity() {
        return MsNoteEntity.DEFINITION.code();
    }

    @Override
    public void requireVisible(long id) {
        // Someone else's note answers as a missing one, as on every note path (plan 10/10, item 5.0).
        notes.getNote(id, SecurityContext.getCurrentUserId());
    }

    @Override
    public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search) {
        return notes.getNotes(SecurityContext.getCurrentUserId(), limit, cursor, filter, sort, search);
    }

    @Override
    public void delete(long id) {
        notes.deleteNote(id, SecurityContext.getCurrentUserId());
    }
}
