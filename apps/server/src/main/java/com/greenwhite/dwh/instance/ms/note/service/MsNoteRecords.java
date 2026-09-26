package com.greenwhite.dwh.instance.ms.note.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.entity.EntityRecords;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
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
        try {
            notes.getNote(id, SecurityContext.getCurrentUserId());
        } catch (ApiException e) {
            // Someone else's note is not revealed: the same answer as a note that does not exist.
            throw ApiException.notFound(ErrorCode.NOT_FOUND, "Заметка не найдена: " + id);
        }
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
