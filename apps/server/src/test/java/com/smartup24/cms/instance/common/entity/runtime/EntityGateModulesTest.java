package com.smartup24.cms.instance.common.entity.runtime;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.entity.EntityFileController;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.module.InstalledModules;
import com.smartup24.cms.instance.common.module.ModuleCatalog;
import com.smartup24.cms.instance.common.module.ModuleManifest;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.platform.api.PlatformVersion;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0032, 6.3, step 1: the module of an entity comes from its declaration — the permission area of its form held by
 * an installed module — not from its menu item, and a switched-off module closes the entity on every path.
 */
class EntityGateModulesTest {

    private static final PlatformVersion V1 = PlatformVersion.parse("1.0.0");

    /** The manifests of the application: {@code iam} also holds the area {@code md}. */
    private static final ModuleCatalog CATALOG = new ModuleCatalog(
            V1, List.of(manifest("iam", List.of("md")), manifest("notes", List.of()), manifest("tasks", List.of())));

    /** Like the users: form md.users, a menu item that names no module. */
    private static final EntityDefinition USERS = entity("md.users", "md.users", "md_users")
            .menu(new EntityMenu("nav.users", "people", "iam", 10, null))
            .build();

    /** Like the task types: no menu item at all. */
    private static final EntityDefinition TYPES =
            entity("ms.task_types", "tasks.types", "ms_task_types").build();

    /** A menu item that names another module than its form: the form decides. */
    private static final EntityDefinition NOTES = entity("ms.notes", "notes", "ms_notes")
            .menu(new EntityMenu("/notes", "nav.notes", "description", "workspace", 30, "tasks"))
            .build();

    private final Set<String> switchedOff = new HashSet<>();
    private final InstalledModules installed = module -> !switchedOff.contains(module);

    @BeforeEach
    void signIn() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                10L,
                "viewer",
                "viewer@example.invalid",
                20L,
                false,
                Set.of("notes.view", "md.users.view", "tasks.types.view"),
                1L,
                false,
                0,
                null));
    }

    @AfterEach
    void signOut() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("The module holds the form's area: users without a menu module, task types without a menu")
    void theModuleComesFromTheDeclaration() {
        EntityGate gate = new EntityGate(new EntityRegistry(List.of(USERS, TYPES, NOTES)), installed, CATALOG);

        assertThat(gate.moduleOf("md.users")).contains("iam");
        assertThat(gate.moduleOf("ms.task_types")).contains("tasks");
        assertThat(gate.moduleOf("ms.notes")).as("the form, not the menu item").contains("notes");
    }

    @Test
    @DisplayName("An entity whose area no installed module holds refuses the start")
    void anUnheldAreaRefusesTheStart() {
        ModuleCatalog withoutTasks =
                new ModuleCatalog(V1, List.of(manifest("iam", List.of("md")), manifest("notes", List.of())));
        EntityRegistry registry = new EntityRegistry(List.of(NOTES, TYPES));

        assertThatThrownBy(() -> new EntityGate(registry, installed, withoutTasks))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ms.task_types")
                .hasMessageContaining("area tasks")
                .hasMessageNotContaining("ms.notes");
    }

    @Test
    @DisplayName("A switched-off module closes its entities: no read, no list, no file, 404 as an unknown entity")
    void aSwitchedOffModuleClosesItsEntities() {
        EntityRegistry registry = new EntityRegistry(List.of(NOTES, TYPES, USERS));
        EntityGate gate = new EntityGate(registry, installed, CATALOG);
        assertThat(gate.findViewable("ms.task_types")).isPresent();

        switchedOff.add("tasks");

        assertThat(gate.findViewable("ms.task_types")).isEmpty();
        assertThat(gate.findVisible("ms.task_types")).isEmpty();
        assertThat(gate.listOpen(TYPES.listCode())).isFalse();
        assertThat(gate.findViewable("ms.notes"))
                .as("an entity whose menu item names the module")
                .isPresent();
        assertThat(gate.listOpen("a.module.own.list")).as("a list of no entity").isTrue();
        assertThatThrownBy(() -> gate.viewable("ms.task_types"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.common.entity_not_found"));
        EntityFileController files = new EntityFileController(registry, gate, null);
        assertThatThrownBy(() -> files.download("ms.task_types", 1L, UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.getMessageKey()).isEqualTo("error.common.record_file_not_found"));

        switchedOff.add("iam");
        assertThat(gate.findViewable("md.users"))
                .as("a menu item without a module")
                .isEmpty();
    }

    private static Entity entity(String code, String form, String table) {
        return Entity.define(code, form)
                .table(table, "t")
                .scope(EntityScope.all())
                .field(text("title", "x").column("title").list(sortable()))
                .section("main", "entity.section.main", "title")
                .defaultSort("title", Entity.Sort.ASC);
    }

    private static ModuleManifest manifest(String code, List<String> areas) {
        return new ModuleManifest(code, code, V1, V1, List.of(), areas, null, null, null, "test:" + code);
    }
}
