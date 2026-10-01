package com.smartup24.cms.instance.md;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityRecords;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Roadmap item 57: a declared entity names its right in the permission matrix; other forms keep the catalog. */
class MdPermissionEntityNamesTest {

    @Test
    void catalogSyncTakesTheNamesADeclaredEntityGives() {
        MdPermissionRepository repository = mock(MdPermissionRepository.class);
        when(repository.getGrantablePairs()).thenReturn(Set.of());
        EntityRecords records = new EntityRecords() {
            public String entity() {
                return MsNoteEntity.DEFINITION.code();
            }

            public void requireVisible(long id) {}
        };
        MdPermissionService service = new MdPermissionService(
                repository, new EntityRegistry(List.of(MsNoteEntity.DEFINITION), List.of(), List.of(records)));

        service.syncFormCatalog(Set.of("notes.update", "md.users.block"));

        verify(repository).registerForm("notes", "ms.note", "Заметки");
        verify(repository).registerFormAction("notes", "update", "Редактирование и закрепление заметки");
        verify(repository).registerForm("md.users", "md", "Пользователи");
        verify(repository).registerFormAction("md.users", "block", "Блокировка");
    }
}
