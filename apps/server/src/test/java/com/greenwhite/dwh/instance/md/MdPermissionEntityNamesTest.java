package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.common.entity.EntityRecords;
import com.greenwhite.dwh.instance.common.entity.EntityRegistry;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.ms.note.service.MsNoteEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Roadmap item 57: a declared entity names its right in the permission matrix; other forms keep the catalog. */
class MdPermissionEntityNamesTest {

    @Test
    void catalogSyncTakesTheNamesADeclaredEntityGives() {
        MdPermissionRepository repository = mock(MdPermissionRepository.class);
        when(repository.getGrantablePairs()).thenReturn(Set.of());
        EntityRecords records = new EntityRecords() {
            public String entity() { return MsNoteEntity.DEFINITION.code(); }
            public void requireVisible(long id) { }
        };
        MdPermissionService service = new MdPermissionService(repository,
                new EntityRegistry(List.of(MsNoteEntity.DEFINITION), List.of(), List.of(records)));

        service.syncFormCatalog(Set.of("notes.update", "iam.users.block"));

        verify(repository).registerForm("notes", "ms.note", "Заметки");
        verify(repository).registerFormAction("notes", "update", "Редактирование и закрепление заметки");
        verify(repository).registerForm("iam.users", "md", "Пользователи");
        verify(repository).registerFormAction("iam.users", "block", "Блокировка");
    }
}
