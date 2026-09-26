package com.greenwhite.dwh.instance.ms.note;

import com.greenwhite.dwh.instance.support.TestDatabases;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.ms.note.repository.MsNoteRepository;
import com.greenwhite.dwh.instance.ms.note.service.MsNoteService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MsNoteIntegrationTest {

    static JdbcClient jdbc;
    static MsNoteService noteService;
    static Long user1Id;
    static Long user2Id;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("dwh_note_test");
        jdbc = JdbcClient.create(ds);
        var mapper = new ObjectMapper();
        var repo = new MsNoteRepository(jdbc, mapper);
        var auditRepo = new AuditLogRepository(jdbc, mapper);
        var auditService = new AuditLogService(auditRepo, null, new AuditDataRedactor());
        noteService = new MsNoteService(repo, auditService);

        user1Id = jdbc.sql("""
                insert into md_users(name, login, email, password_hash, state)
                values('Test User 1', 'user1', 'user1@example.com', 'hash', 'A')
                returning id
                """).query(Long.class).single();

        user2Id = jdbc.sql("""
                insert into md_users(name, login, email, password_hash, state)
                values('Test User 2', 'user2', 'user2@example.com', 'hash', 'A')
                returning id
                """).query(Long.class).single();
    }

    @Test
    @DisplayName("1. Создание, получение, закрепление и поиск заметок")
    void crudAndSearchNotes() {
        var note = noteService.createNote(
                "Архитектурный манифест",
                "Модульный монолит со строгой изоляцией доменов",
                "blue",
                false,
                Map.of("category", "engineering"),
                user1Id
        );

        assertThat(note.id()).isNotNull();
        assertThat(note.title()).isEqualTo("Архитектурный манифест");
        assertThat(note.isPinned()).isFalse();

        // Закрепление
        var pinned = noteService.togglePinned(note.id(), user1Id);
        assertThat(pinned.isPinned()).isTrue();

        // Поиск по ключевому слову
        var found = noteService.getNotes(user1Id, null, null, null, null, "манифест").items();
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().id()).isEqualTo(note.id());

        // Обновление
        var updated = noteService.updateNote(note.id(), "Обновленный манифест", null, "yellow", null, null, user1Id);
        assertThat(updated.title()).isEqualTo("Обновленный манифест");
        assertThat(updated.color()).isEqualTo("yellow");

        // Удаление
        noteService.deleteNote(note.id(), user1Id);
        assertThatThrownBy(() -> noteService.getNote(note.id(), user1Id))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("2. Изоляция данных: пользователь не может читать или менять чужие заметки")
    void userIsolationEnforced() {
        var user1Note = noteService.createNote("Приватная заметка 1", "Секретный контент", "default", false, null, user1Id);

        // Пользователь 2 не может получить чужую заметку
        assertThatThrownBy(() -> noteService.getNote(user1Note.id(), user2Id))
                .isInstanceOf(ApiException.class);

        // Пользователь 2 не видит чужую заметку в своем списке
        var user2Notes = noteService.getNotes(user2Id, null, null, null, null, null).items();
        assertThat(user2Notes.stream().map(MsNoteService.NoteView::id)).doesNotContain(user1Note.id());

        // Пользователь 2 не может изменить чужую заметку
        assertThatThrownBy(() -> noteService.updateNote(user1Note.id(), "Хак", null, null, null, null, user2Id))
                .isInstanceOf(ApiException.class);

        // Пользователь 2 не может удалить чужую заметку
        assertThatThrownBy(() -> noteService.deleteNote(user1Note.id(), user2Id))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("3. Отключенный модуль блокирует операции над заметками")
    void disabledModuleBlocksNoteOperations() {
        var modRepo = new com.greenwhite.dwh.instance.md.repository.ModuleRegistryRepository(jdbc, new ObjectMapper());
        var modService = new com.greenwhite.dwh.instance.md.service.ModuleRegistryService(modRepo, null);
        var restrictedNoteService = new MsNoteService(
                new MsNoteRepository(jdbc, new ObjectMapper()),
                new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null, new AuditDataRedactor()),
                null,
                modService
        );

        // Disable notes module in DB
        jdbc.sql("update md_installed_modules set status = 'DISABLED' where code = 'notes'").update();

        try {
            assertThatThrownBy(() -> restrictedNoteService.getNotes(user1Id, null, null, null, null, null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Модуль 'notes' отключен администратором");

            assertThatThrownBy(() -> restrictedNoteService.createNote("Test", "Body", "blue", false, null, user1Id))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Модуль 'notes' отключен администратором");
        } finally {
            // Restore notes module
            jdbc.sql("update md_installed_modules set status = 'ACTIVE' where code = 'notes'").update();
        }
    }

    @Test
    @DisplayName("4. Список на реестре: закреплённые первыми, затем свежие; курсор и фильтр «закреплённые»")
    void registryListKeepsPinnedFirst() {
        var older = noteService.createNote("nl старая", "", "default", false, null, user2Id);
        var pinned = noteService.createNote("nl закреплённая", "", "default", true, null, user2Id);
        var newer = noteService.createNote("nl свежая", "", "default", false, null, user2Id);
        jdbc.sql("update ms_notes set modified_at = now() - interval '2 hour' where id = :id").param("id", older.id()).update();
        jdbc.sql("update ms_notes set modified_at = now() - interval '3 hour' where id = :id").param("id", pinned.id()).update();

        var first = noteService.getNotes(user2Id, 2, null, null, null, "nl ");
        var second = noteService.getNotes(user2Id, 2, first.nextCursor(), null, null, "nl ");
        assertThat(first.items()).extracting(MsNoteService.NoteView::id).containsExactly(pinned.id(), newer.id());
        assertThat(second.items()).extracting(MsNoteService.NoteView::id).containsExactly(older.id());
        assertThat(first.totalEstimated()).isEqualTo(3);

        var onlyPinned = noteService.getNotes(user2Id, null, null,
                "[{\"field\":\"isPinned\",\"op\":\"eq\",\"value\":true}]", null, "nl ");
        assertThat(onlyPinned.items()).extracting(MsNoteService.NoteView::id).containsExactly(pinned.id());
        assertThat(noteService.getNotes(user1Id, null, null, null, null, "nl ").items()).isEmpty();
    }
}
