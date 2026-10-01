package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.api.NavigationItemView;
import com.smartup24.cms.instance.md.repository.MdPermissionRepository;
import com.smartup24.cms.instance.md.repository.NavigationItemRepository;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import com.smartup24.cms.instance.md.service.NavigationItemService;
import com.smartup24.cms.instance.md.service.NavigationItemService.CreateNavigationItemCommand;
import com.smartup24.cms.instance.md.service.NavigationItemService.PermissionChoice;
import com.smartup24.cms.instance.md.service.NavigationItemService.UpdateNavigationItemCommand;
import com.smartup24.cms.instance.support.TestDatabases;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** The menu shows an item only to those who have its permission. */
class NavigationItemVisibilityIntegrationTest {

    private static final String GUARDED = "md.navigation.manage";

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

        signIn("md.profile.view");
        assertThat(visibleCodes()).contains("vis-open").doesNotContain("vis-guarded");

        signIn("md.profile.view", GUARDED);
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

        signIn("md.profile.view");
        assertThat(visibleCodes()).doesNotContain("vis-parent", "vis-child", "vis-off");

        signIn(GUARDED);
        assertThat(visibleCodes()).contains("vis-parent", "vis-child").doesNotContain("vis-off");
    }

    @Test
    @DisplayName("Встроенный пункт по коду: чужой неотличим от несуществующего")
    void itemByCodeIsHiddenFromThoseWithoutItsPermission() {
        create("vis-report", GUARDED, null);

        signIn("md.profile.view");
        assertThat(service.getVisibleItemByCode("vis-report")).isEmpty();
        assertThat(service.getVisibleItemByCode("vis-missing")).isEmpty();

        signIn(GUARDED);
        assertThat(service.getVisibleItemByCode("vis-report")).isPresent();
    }

    @Test
    @DisplayName("Право пункта — живая пара каталога, иначе 422 с адресом поля")
    void permissionMustBeALiveCatalogPair() {
        assertThatThrownBy(() -> create("vis-bad", "md.navigation.fly", null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getFieldErrors())
                                .extracting(FieldErrorItem::field, FieldErrorItem::code)
                                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                                        "requiredPermission", NavigationItemService.PERMISSION_UNKNOWN)));

        NavigationItemView item = create("vis-good", "  " + GUARDED + " ", null);
        assertThat(item.requiredPermission()).isEqualTo(GUARDED);
        // ADR-0028: a code of the previous release is stored under its successor until the sunset.
        assertThat(create("vis-legacy", "platform.navigation.manage", null).requiredPermission())
                .isEqualTo(GUARDED);

        assertThatThrownBy(() -> service.updateItem(
                        item.id(),
                        new UpdateNavigationItemCommand(
                                item.code(),
                                item.title(),
                                null,
                                null,
                                null,
                                null,
                                "INTERNAL_ROUTE",
                                "/tasks",
                                false,
                                "nobody.nothing",
                                10,
                                null),
                        null,
                        1L))
                .isInstanceOf(ApiException.class);

        NavigationItemView cleared = service.updateItem(
                item.id(),
                new UpdateNavigationItemCommand(
                        item.code(),
                        item.title(),
                        null,
                        null,
                        null,
                        null,
                        "INTERNAL_ROUTE",
                        "/tasks",
                        false,
                        " ",
                        10,
                        null),
                null,
                1L);
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

    @Test
    @DisplayName("3.4: PUT …/active sets the state; a repeat changes and audits nothing")
    void settingTheStateIsIdempotent() {
        NavigationItemView item = create("vis-set", null, null);

        assertThat(service.setActive(item.id(), null, true).state()).isEqualTo("A");
        assertThat(service.setActive(item.id(), null, false).state()).isEqualTo("P");
        assertThat(service.setActive(item.id(), null, false).state()).isEqualTo("P");

        Integer audited = jdbc.sql("""
                        select count(*) from audit_log
                        where table_name = 'md_navigation_items' and row_pk = :id and event = 'U'
                        """)
                .param("id", String.valueOf(item.id()))
                .query(Integer.class)
                .single();
        assertThat(audited).isEqualTo(1);
    }

    private static NavigationItemView create(String code, String permission, Long parentId) {
        return service.createItem(
                new CreateNavigationItemCommand(
                        code, code, null, "custom", parentId, null, "INTERNAL_ROUTE", "/tasks", false, permission, 10),
                null);
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
