package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityRecords;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.md.api.MdRoleDtos.FormCatalogItem;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository.FormTreeItem;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap item 57: a declared entity names its right in the permission matrix; other forms keep the catalog. Plan
 * 10/10, item 5.0: the names are dictionary keys (ADR-0031), translated in ru, uz and en.
 */
class MdPermissionEntityNamesTest {

    private static final MdI18nCatalog CATALOG = new MdI18nCatalog(JsonMapper.shared());

    /** The key rule of ADR-0031: {@code <module>.<screen>.<element>}, English snake_case segments. */
    private static final Pattern CONVENTION =
            Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:_[a-z0-9]+)*){2,}$");

    private static EntityRegistry notes() {
        EntityRecords records = new EntityRecords() {
            public String entity() {
                return MsNoteEntity.DEFINITION.code();
            }

            public void requireVisible(long id) {}
        };
        return new EntityRegistry(List.of(MsNoteEntity.DEFINITION), List.of(), List.of(records));
    }

    @Test
    void catalogSyncStoresTheRussianWordsOfTheKeysADeclaredEntityGives() {
        MdPermissionRepository repository = mock(MdPermissionRepository.class);
        when(repository.getGrantablePairs()).thenReturn(Set.of());
        MdPermissionService service = new MdPermissionService(repository, notes(), CATALOG);

        service.syncFormCatalog(Set.of("notes.update", "md.users.block"));

        verify(repository).registerForm("notes", "ms.note", "Заметки");
        verify(repository).registerFormAction("notes", "update", "Редактирование и закрепление заметки");
        verify(repository).registerForm("md.users", "md", "Пользователи");
        verify(repository).registerFormAction("md.users", "block", "Блокировка");
    }

    @Test
    void theCatalogGivesTheKeysOfAnEntitysRightAndNoneForOtherForms() {
        MdPermissionRepository repository = mock(MdPermissionRepository.class);
        when(repository.getAllFormsWithActions())
                .thenReturn(List.of(
                        new FormTreeItem("notes", "ms.note", "Заметки", "delete", "Удаление заметки", false),
                        new FormTreeItem("md.users", "md", "Пользователи", "block", "Блокировка", false)));
        MdPermissionService service = new MdPermissionService(repository, notes(), CATALOG);

        List<FormCatalogItem> items = service.getFormCatalogItems();

        assertThat(items.getFirst().formNameKey()).isEqualTo("notes.rights.form");
        assertThat(items.getFirst().actionNameKey()).isEqualTo("notes.rights.delete");
        assertThat(items.getFirst().formName()).isEqualTo("Заметки");
        assertThat(items.get(1).formNameKey()).isNull();
        assertThat(items.get(1).actionNameKey()).isNull();
    }

    @Test
    void everyEntityRightKeyFollowsTheConventionAndIsTranslatedInEveryLanguage() throws Exception {
        List<String> problems = new ArrayList<>();
        for (EntityDefinition entity : EntityActionPermissionContractTest.declaredEntities()) {
            if (entity.rights() == null) {
                problems.add(entity.code() + ": no names of its right");
                continue;
            }
            for (String key : entity.rights().keys()) {
                if (!CONVENTION.matcher(key).matches()) problems.add(entity.code() + ": " + key + " off ADR-0031");
                for (String language : List.of("ru", "uz", "en")) {
                    Map<String, String> words = CATALOG.bundled(language);
                    if (words.getOrDefault(key, "").isBlank()) problems.add(language + ": " + key + " missing");
                }
            }
        }
        assertThat(problems).isEmpty();
    }
}
