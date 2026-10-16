package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.md.pref.MdFormCatalog;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository.FormTreeItem;
import com.smartup24.cms.instance.md.service.MdI18nCatalog;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Static permission forms use the same translated contract as entities (ADR-0031). */
class MdPermissionCatalogNamesTest {

    private static final MdI18nCatalog CATALOG = new MdI18nCatalog(JsonMapper.shared());

    private final MdPermissionRepository repository = mock(MdPermissionRepository.class);
    private final MdPermissionService service =
            new MdPermissionService(repository, new EntityRegistry(List.of(), List.of()), CATALOG);

    @Test
    void staticFormsExposeKeysForTheViewersLanguage() {
        when(repository.getAllFormsWithActions())
                .thenReturn(List.of(
                        new FormTreeItem("md.roles", "md", "Роли и права", "grant", "Настройка матрицы прав", false),
                        new FormTreeItem("upl.sources", "upl", "Источники и форматы", "view", "Просмотр", false)));

        var items = service.getFormCatalogItems();

        assertThat(items.getFirst().formNameKey()).isEqualTo("md.roles.rights.form");
        assertThat(items.getFirst().actionNameKey()).isEqualTo("md.roles.rights.grant");
        assertThat(items.get(1).formNameKey()).isEqualTo("upl.sources.rights.form");
        assertThat(items.get(1).actionNameKey()).isEqualTo("upl.sources.rights.view");
        assertThat(CATALOG.bundled("en").get(items.getFirst().formNameKey())).isEqualTo("Roles and permissions");
        assertThat(CATALOG.bundled("uz").get(items.getFirst().formNameKey())).isEqualTo("Rollar va huquqlar");
        assertThat(items.getFirst().formName()).isEqualTo("Роли и права");
    }

    @Test
    void everyDeclaredStaticPermissionHasTranslatedKeys() {
        var rows = MdFormCatalogTest.declaredPairsFromSources().stream()
                .map(pair -> {
                    int dot = pair.lastIndexOf('.');
                    return new FormTreeItem(
                            pair.substring(0, dot), "module", "form", pair.substring(dot + 1), "action", false);
                })
                .filter(row -> MdFormCatalog.hasHumanName(row.formCode(), row.action()))
                .toList();
        when(repository.getAllFormsWithActions()).thenReturn(rows);

        assertThat(rows).isNotEmpty();
        for (var item : service.getFormCatalogItems()) {
            assertThat(item.formNameKey()).as(item.formCode()).isNotBlank();
            assertThat(item.actionNameKey())
                    .as(item.formCode() + "." + item.action())
                    .isNotBlank();
            for (String language : List.of("ru", "uz", "en")) {
                assertThat(CATALOG.bundled(language).get(item.formNameKey()))
                        .as(language + ": " + item.formNameKey())
                        .isNotBlank();
                assertThat(CATALOG.bundled(language).get(item.actionNameKey()))
                        .as(language + ": " + item.actionNameKey())
                        .isNotBlank();
            }
        }
    }

    @Test
    void unknownFormsAndActionsKeepTheirStoredNamesWithoutInventedKeys() {
        when(repository.getAllFormsWithActions())
                .thenReturn(List.of(
                        new FormTreeItem("unknown.form", "unknown", "Unknown form", "view", "View", true),
                        new FormTreeItem("md.roles", "md", "Роли и права", "unknown", "Unknown action", true)));

        var items = service.getFormCatalogItems();

        assertThat(items.getFirst().formNameKey()).isNull();
        assertThat(items.getFirst().actionNameKey()).isNull();
        assertThat(items.getFirst().formName()).isEqualTo("Unknown form");
        assertThat(items.getFirst().actionName()).isEqualTo("View");
        assertThat(items.get(1).formNameKey()).isEqualTo("md.roles.rights.form");
        assertThat(items.get(1).actionNameKey()).isNull();
        assertThat(items.get(1).actionName()).isEqualTo("Unknown action");
        assertThat(items).allMatch(item -> item.isDeprecated());
    }

    @Test
    void synchronizationStoresRussianNamesWithoutChangingPermissionCodes() {
        when(repository.getGrantablePairs()).thenReturn(Set.of());

        service.syncFormCatalog(Set.of("md.roles.grant", "upl.sources.view", "unknown.form.view"));

        verify(repository).registerForm("md.roles", "md", "Роли и права");
        verify(repository).registerFormAction("md.roles", "grant", "Настройка матрицы прав");
        verify(repository).registerForm("upl.sources", "upl", "Источники и форматы");
        verify(repository).registerFormAction("upl.sources", "view", "Просмотр");
        verify(repository).registerForm("unknown.form", "unknown", "unknown.form");
        verify(repository).registerFormAction("unknown.form", "view", "view");
    }
}
