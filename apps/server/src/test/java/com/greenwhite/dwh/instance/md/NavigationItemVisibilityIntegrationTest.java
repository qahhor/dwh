package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.support.TestDatabases;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.NavigationItemRepository;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.NavigationItemService;
import com.greenwhite.dwh.instance.md.service.NavigationItemService.CreateNavigationItemCommand;
import com.greenwhite.dwh.instance.md.service.NavigationItemService.NavigationItemView;
import com.greenwhite.dwh.instance.md.service.NavigationItemService.PermissionChoice;
import com.greenwhite.dwh.instance.md.service.NavigationItemService.UpdateNavigationItemCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-MOD-02: меню показывает пункт только тому, у кого есть его право. */
class NavigationItemVisibilityIntegrationTest {

    private static final String GUARDED = "platform.navigation.manage";

    static JdbcClient jdbc;
    static NavigationItemService service;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("dwh_navigation_test");
        jdbc = JdbcClient.create(ds);
        var mapper = new ObjectMapper();
        var auditService = new AuditLogService(new AuditLogRepository(jdbc, mapper), null, new AuditDataRedactor());
        service = new NavigationItemService(
                new NavigationItemRepository(jdbc),
                auditService,
                new MdPermissionService(new MdPermissionRepository(jdbc)));
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
        jdbc.sql("delete from md_navigation_items where code like 'vis-%'").update();
    }

    @Test
    @DisplayName("Пункт с правом виден только его владельцу, пункт без права — всем")
    void itemWithPermissionIsShownOnlyToItsHolder() {
        create("vis-open", null, null);
        create("vis-guarded", GUARDED, null);

        signIn("iam.profile.view");
        assertThat(visibleCodes()).contains("vis-open").doesNotContain("vis-guarded");

        signIn("iam.profile.view", GUARDED);
        assertThat(visibleCodes()).contains("vis-open", "vis-guarded");

        signIn("*.*");
        assertThat(visibleCodes()).contains("vis-open", "vis-guarded");
    }

    @Test
    @DisplayName("Вложенный пункт скрыт вместе с родителем, выключенный пункт не виден никому")
    void childFollowsItsParentAndInactiveItemsAreHidden() {
        NavigationItemView parent = create("vis-parent", GUARDED, null);
        create("vis-child", null, parent.id());
        NavigationItemView off = create("vis-off", null, null);
        service.toggleState(off.id(), null);

        signIn("iam.profile.view");
        assertThat(visibleCodes()).doesNotContain("vis-parent", "vis-child", "vis-off");

        signIn(GUARDED);
        assertThat(visibleCodes()).contains("vis-parent", "vis-child").doesNotContain("vis-off");
    }

    @Test
    @DisplayName("Встроенный пункт по коду: чужой неотличим от несуществующего")
    void itemByCodeIsHiddenFromThoseWithoutItsPermission() {
        create("vis-report", GUARDED, null);

        signIn("iam.profile.view");
        assertThat(service.getVisibleItemByCode("vis-report")).isEmpty();
        assertThat(service.getVisibleItemByCode("vis-missing")).isEmpty();

        signIn(GUARDED);
        assertThat(service.getVisibleItemByCode("vis-report")).isPresent();
    }

    @Test
    @DisplayName("Право пункта — живая пара каталога, иначе 422 с адресом поля")
    void permissionMustBeALiveCatalogPair() {
        assertThatThrownBy(() -> create("vis-bad", "platform.navigation.fly", null))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getFieldErrors())
                        .extracting(FieldErrorItem::field, FieldErrorItem::code)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple(
                                "requiredPermission", NavigationItemService.PERMISSION_UNKNOWN)));

        NavigationItemView item = create("vis-good", "  " + GUARDED + " ", null);
        assertThat(item.requiredPermission()).isEqualTo(GUARDED);

        assertThatThrownBy(() -> service.updateItem(item.id(), new UpdateNavigationItemCommand(
                item.code(), item.title(), null, null, null, null, "INTERNAL_ROUTE", "/tasks", false,
                "nobody.nothing", 10, null), null))
                .isInstanceOf(ApiException.class);

        NavigationItemView cleared = service.updateItem(item.id(), new UpdateNavigationItemCommand(
                item.code(), item.title(), null, null, null, null, "INTERNAL_ROUTE", "/tasks", false,
                " ", 10, null), null);
        assertThat(cleared.requiredPermission()).isNull();
    }

    @Test
    @DisplayName("Выбор права — живые пары каталога с названиями")
    void permissionChoicesAreLiveCatalogPairs() {
        List<PermissionChoice> choices = service.getPermissionChoices();
        assertThat(choices).extracting(PermissionChoice::permission).contains(GUARDED);
        assertThat(choices).allSatisfy(choice -> {
            assertThat(choice.formName()).isNotBlank();
            assertThat(choice.actionName()).isNotBlank();
        });
    }

    private static NavigationItemView create(String code, String permission, Long parentId) {
        return service.createItem(new CreateNavigationItemCommand(
                code, code, null, "custom", parentId, null, "INTERNAL_ROUTE", "/tasks", false, permission, 10), null);
    }

    private static List<String> visibleCodes() {
        return NavigationItemService.visibleToViewer(service.getAllItems()).stream()
                .map(NavigationItemView::code)
                .toList();
    }

    private static void signIn(String... permissions) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L, "viewer", "viewer@example.test", 1L, false, Set.of(permissions), 1L, false, 1L, null));
    }
}
