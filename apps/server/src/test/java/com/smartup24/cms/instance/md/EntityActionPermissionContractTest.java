package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.md.pref.PermissionAreas;
import com.smartup24.cms.instance.md.service.MdFormCatalogSynchronizer;
import com.smartup24.cms.instance.support.TestFixtureExcludeFilter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Every action an entity offers (ADR-0019, 2.2) is a right the server really checks and the permission matrix names:
 * {@code form-meta} shows a button by that right. Since the general runtime (ADR-0032, 6.10) the right is checked by
 * the runtime from the declaration, not by an annotation: every pair of a declaration — {@code view}, the right of each
 * action — is named in its {@code EntityRights}, reaches the form catalog ({@link MdFormCatalogSynchronizer}) and lives
 * in a form of the module the declaration names.
 */
class EntityActionPermissionContractTest {

    @Test
    void everyEntityActionAndViewIsNamedAndReachesTheCatalog() throws Exception {
        List<EntityDefinition> entities = declaredEntities();
        assertThat(entities).extracting(EntityDefinition::code).contains("ms.notes");
        Set<String> catalog = MdFormCatalogSynchronizer.entityPairs(entities);

        List<String> missing = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            EntityDefinition.EntityRights rights = entity.rights();
            if (rights == null) {
                missing.add(entity.code() + " names no rights for the permission matrix");
                continue;
            }
            List<String> permissions = new ArrayList<>(List.of("view"));
            entity.actions().forEach(action -> permissions.add(action.permission()));
            for (String permission : permissions) {
                if (!rights.actionKeys().containsKey(permission)) {
                    missing.add(entity.code() + " " + permission + " has no name");
                }
                if (!catalog.contains(entity.form() + "." + permission)) {
                    missing.add(entity.code() + " " + entity.form() + "." + permission + " misses the catalog");
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    /** Plan 10/10, item 4.4 (ADR-0028): an entity's form follows the rule, and its area leads to the named module. */
    @Test
    void everyEntityFormIsOwnedByTheModuleItsRightsName() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (EntityDefinition entity : declaredEntities()) {
            Optional<String> owner = PermissionAreas.ownerOf(entity.form());
            if (owner.isEmpty()
                    || (entity.rights() != null
                            && !owner.get().equals(entity.rights().module()))) {
                wrong.add(entity.code() + " → " + entity.form() + " owned by " + owner);
            }
        }
        assertThat(wrong).isEmpty();
    }

    /** The entities the application declares: the {@code @Bean EntityDefinition} methods of its configurations. */
    static List<EntityDefinition> declaredEntities() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        // The application's own declarations, not the fixtures of tests (EntityFieldRightsIntegrationTest).
        scanner.addExcludeFilter(new TestFixtureExcludeFilter());
        List<EntityDefinition> entities = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.smartup24.cms.instance")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                // A declaration may take the providers of the beans its custom scope reads (the users' scope): they
                // are asked only when a viewer's rows are read, never while the entity is declared.
                if (method.isAnnotationPresent(Bean.class)
                        && method.getReturnType() == EntityDefinition.class
                        && Arrays.stream(method.getParameterTypes()).allMatch(ObjectProvider.class::equals)) {
                    var ctor = type.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    method.setAccessible(true);
                    entities.add((EntityDefinition)
                            method.invoke(ctor.newInstance(), new Object[method.getParameterCount()]));
                }
            }
        }
        return entities;
    }
}
