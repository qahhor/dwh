package com.smartup24.cms.instance.ms.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.audit.service.RecordHistoryService;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.FormFieldExtender;
import com.smartup24.cms.instance.common.entity.FormFieldType;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.repository.ModuleRegistryRepository;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.ms.note.repository.MsNoteRepository;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.ms.note.service.MsNoteRecords;
import com.smartup24.cms.instance.ms.note.service.MsNoteService;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class MsNoteIntegrationTest {

    static JdbcClient jdbc;
    static MsNoteService noteService;
    static AuditLogService auditService;
    static Long user1Id;
    static Long user2Id;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("smc_note_test");
        jdbc = JdbcClient.create(ds);
        var mapper = new ObjectMapper();
        var repo = new MsNoteRepository(jdbc, mapper);
        var auditRepo = new AuditLogRepository(jdbc, mapper);
        auditService = new AuditLogService(auditRepo, null, new AuditDataRedactor());
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
                user1Id);

        assertThat(note.id()).isNotNull();
        assertThat(note.title()).isEqualTo("Архитектурный манифест");
        assertThat(note.isPinned()).isFalse();

        // PUT /notes/{id}/pin (plan item 3.4): the state is set, a repeat leaves it.
        assertThat(noteService.setPinned(note.id(), user1Id, true).isPinned()).isTrue();
        assertThat(noteService.setPinned(note.id(), user1Id, true).isPinned()).isTrue();
        assertThat(noteService.setPinned(note.id(), user1Id, false).isPinned()).isFalse();
        assertThat(noteService.setPinned(note.id(), user1Id, false).isPinned()).isFalse();

        // Search by keyword
        var found = noteService
                .getNotes(user1Id, null, null, null, null, "манифест")
                .items();
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().id()).isEqualTo(note.id());

        // Update
        long revision = noteService.getNote(note.id(), user1Id).revision();
        var updated = noteService.updateNote(
                note.id(), "Обновленный манифест", null, "yellow", null, null, user1Id, revision);
        assertThat(updated.title()).isEqualTo("Обновленный манифест");
        assertThat(updated.color()).isEqualTo("yellow");

        // Delete
        noteService.deleteNote(note.id(), user1Id);
        assertThatThrownBy(() -> noteService.getNote(note.id(), user1Id)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("2. Изоляция данных: пользователь не может читать или менять чужие заметки")
    void userIsolationEnforced() {
        var user1Note =
                noteService.createNote("Приватная заметка 1", "Секретный контент", "default", false, null, user1Id);

        // User 2 cannot get someone else's note
        assertThatThrownBy(() -> noteService.getNote(user1Note.id(), user2Id)).isInstanceOf(ApiException.class);

        // User 2 does not see someone else's note in their own list
        var user2Notes =
                noteService.getNotes(user2Id, null, null, null, null, null).items();
        assertThat(user2Notes.stream().map(MsNoteService.NoteView::id)).doesNotContain(user1Note.id());

        // User 2 cannot change someone else's note
        assertThatThrownBy(() -> noteService.updateNote(user1Note.id(), "Хак", null, null, null, null, user2Id, 1L))
                .isInstanceOf(ApiException.class);

        // User 2 cannot delete someone else's note
        assertThatThrownBy(() -> noteService.deleteNote(user1Note.id(), user2Id))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("3. Отключенный модуль блокирует операции над заметками")
    void disabledModuleBlocksNoteOperations() {
        var modRepo = new ModuleRegistryRepository(jdbc, new ObjectMapper());
        var modService = new ModuleRegistryService(modRepo, null);
        var restrictedNoteService = new MsNoteService(
                new MsNoteRepository(jdbc, new ObjectMapper()),
                new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null, new AuditDataRedactor()),
                null,
                modService);

        // Disable notes module in DB
        jdbc.sql("update md_installed_modules set status = 'DISABLED' where code = 'notes'")
                .update();

        try {
            assertThatThrownBy(() -> restrictedNoteService.getNotes(user1Id, null, null, null, null, null))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error -> assertThat(error.getMessageKey()).isEqualTo("error.note.module_disabled"));

            assertThatThrownBy(() -> restrictedNoteService.createNote("Test", "Body", "blue", false, null, user1Id))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error -> assertThat(error.getMessageKey()).isEqualTo("error.note.module_disabled"));
        } finally {
            // Restore notes module
            jdbc.sql("update md_installed_modules set status = 'ACTIVE' where code = 'notes'")
                    .update();
        }
    }

    @Test
    @DisplayName("4. Список на реестре: закреплённые первыми, затем свежие; курсор и фильтр «закреплённые»")
    void registryListKeepsPinnedFirst() {
        var older = noteService.createNote("nl старая", "", "default", false, null, user2Id);
        var pinned = noteService.createNote("nl закреплённая", "", "default", true, null, user2Id);
        var newer = noteService.createNote("nl свежая", "", "default", false, null, user2Id);
        jdbc.sql("update ms_notes set modified_at = now() - interval '2 hour' where id = :id")
                .param("id", older.id())
                .update();
        jdbc.sql("update ms_notes set modified_at = now() - interval '3 hour' where id = :id")
                .param("id", pinned.id())
                .update();

        var first = noteService.getNotes(user2Id, 2, null, null, null, "nl ");
        var second = noteService.getNotes(user2Id, 2, first.nextCursor(), null, null, "nl ");
        assertThat(first.items()).extracting(MsNoteService.NoteView::id).containsExactly(pinned.id(), newer.id());
        assertThat(second.items()).extracting(MsNoteService.NoteView::id).containsExactly(older.id());
        assertThat(first.totalEstimated()).isEqualTo(3);

        var onlyPinned = noteService.getNotes(
                user2Id, null, null, "[{\"field\":\"isPinned\",\"op\":\"eq\",\"value\":true}]", null, "nl ");
        assertThat(onlyPinned.items()).extracting(MsNoteService.NoteView::id).containsExactly(pinned.id());
        assertThat(noteService.getNotes(user1Id, null, null, null, null, "nl ").items())
                .isEmpty();
    }

    @Test
    @DisplayName("5. Сохранение проверяется по объявлению сущности: 422 с ошибкой на поле")
    void savesAreCheckedByTheEntityDeclaration() {
        assertThatThrownBy(() -> noteService.createNote(" ", "", "default", false, null, user1Id))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(item -> item.field() + ":" + item.code())
                                .containsExactly("title:required"));
        assertThatThrownBy(() -> noteService.createNote("x".repeat(256), "", "orange", false, null, user1Id))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(item -> item.field() + ":" + item.code())
                                .containsExactly("title:too_long", "color:invalid"));

        var note = noteService.createNote("Проверка", "", "default", false, null, user1Id);
        assertThat(noteService
                        .updateNote(note.id(), null, "только текст", null, null, null, user1Id, 1L)
                        .title())
                .isEqualTo("Проверка");
        assertThatThrownBy(() -> noteService.updateNote(note.id(), "", null, null, null, null, user1Id, 1L))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getFieldErrors())
                                .extracting(item -> item.field())
                                .containsExactly("title"));
    }

    @Test
    @DisplayName("7. Аудит пишет все объявленные поля, текст и доп. поля; история называет каждое поле")
    void auditKeepsEveryFieldAndHistoryNamesThem() {
        var extender = (FormFieldExtender) entity ->
                List.of(FormField.of("cfRegion", "", FormFieldType.TEXT).custom("Регион", "region"));
        var registry = new EntityRegistry(
                List.of(MsNoteEntity.DEFINITION), List.of(extender), List.of(new MsNoteRecords(noteService)));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("entities", registry);
        noteService.setEntityRegistry(beans.getBeanProvider(EntityRegistry.class));
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                user1Id, "user1", "user1@example.com", 1L, false, Set.of("notes.view"), 1L, false, 0, null));
        try {
            var note = noteService.createNote(
                    "Аудит", "Первый **текст**", "blue", false, Map.of("region", "Tashkent"), user1Id);
            noteService.updateNote(
                    note.id(), null, "Второй текст", null, null, Map.of("region", "Bukhara"), user1Id, 1L);

            var history = new RecordHistoryService(auditService, List.of(), registry)
                    .history("ms.notes", String.valueOf(note.id()), null, null)
                    .items();
            assertThat(history)
                    .extracting(RecordHistoryService.HistoryEntry::event)
                    .containsExactly("U", "I");
            var created = history.get(1).changes();
            assertThat(created)
                    .extracting(RecordHistoryService.FieldChange::field)
                    .containsExactly("title", "contentMd", "color", "isPinned", "cfRegion");
            assertThat(created)
                    .extracting(change -> change.labelKey() != null ? change.labelKey() : change.label())
                    .containsExactly(
                            "notes.col.title", "notes.col.content", "notes.col.color", "notes.col.pinned", "Регион");
            var changed = history.get(0).changes();
            assertThat(changed)
                    .extracting(
                            RecordHistoryService.FieldChange::field,
                            RecordHistoryService.FieldChange::oldValue,
                            RecordHistoryService.FieldChange::newValue)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("contentMd", "Первый **текст**", "Второй текст"),
                            org.assertj.core.groups.Tuple.tuple("cfRegion", "Tashkent", "Bukhara"));
            noteService.deleteNote(note.id(), user1Id);
            String deleted = jdbc.sql("""
                            select old_row::text from audit_log
                            where table_name = 'ms_notes' and row_pk = :id and event = 'D'
                            """)
                    .param("id", String.valueOf(note.id()))
                    .query(String.class)
                    .single();
            assertThat(deleted).contains("\"contentMd\": \"Второй текст\"", "\"cfRegion\": \"Bukhara\"");
            String columns = jdbc.sql("""
                            select array_to_string(changed_columns, ',') from audit_log
                            where table_name = 'ms_notes' and row_pk = :id and event = 'U'
                            """)
                    .param("id", String.valueOf(note.id()))
                    .query(String.class)
                    .single();
            assertThat(columns).isEqualTo("contentMd,cfRegion");
        } finally {
            SecurityContext.clear();
            noteService.setEntityRegistry(null);
        }
    }

    @Test
    @DisplayName("6. Записи для истории, экспорта и массового удаления: чужая заметка — как несуществующая")
    void recordsHideOtherPeoplesNotes() {
        var records = new MsNoteRecords(noteService);
        var mine = noteService.createNote("rec моя", "", "default", false, null, user1Id);
        var theirs = noteService.createNote("rec чужая", "", "default", false, null, user2Id);
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                user1Id, "user1", "user1@example.com", 1L, false, Set.of(), 1L, false, 0, null));
        try {
            records.requireVisible(mine.id());
            assertThatThrownBy(() -> records.requireVisible(theirs.id()))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThat(records.page(50, null, null, null, "rec ").items()).hasSize(1);

            records.delete(mine.id());
            assertThatThrownBy(() -> records.delete(theirs.id())).isInstanceOf(ApiException.class);
            assertThat(noteService.getNote(theirs.id(), user2Id).title()).isEqualTo("rec чужая");
        } finally {
            SecurityContext.clear();
        }
    }
}
