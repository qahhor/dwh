package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.md.pref.PermissionAreas;
import com.smartup24.cms.instance.md.service.MdFormCatalogSynchronizer;
import com.smartup24.cms.instance.support.TestFixtureExcludeFilter;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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
public class EntityActionPermissionContractTest {

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

    /** The entities the application declares today; a new one is added here when it lands on main. */
    public static final Set<String> DECLARED_CODES = Set.of(
            "ms.notes",
            "ms.task_types",
            "ms.task_statuses",
            "ms.projects",
            "ms.tasks",
            "md.users",
            "example.orders",
            "example.products",
            "example.requests");

    /**
     * Discovery misses nothing: exactly the codes of today are found (plus those the cms CLI smoke generates in its
     * copy and names in {@code -Dcms.smoke.entities}), and as many entities as the sources hold {@code @Bean
     * EntityDefinition} methods.
     */
    @Test
    void discoveryFindsEveryDeclaredEntity() throws Exception {
        List<EntityDefinition> entities = declaredEntities();
        Set<String> expected = new TreeSet<>(DECLARED_CODES);
        for (String code : System.getProperty("cms.smoke.entities", "").split(",")) {
            if (!code.isBlank()) {
                expected.add(code.strip());
            }
        }
        assertThat(entities).extracting(EntityDefinition::code).containsExactlyInAnyOrderElementsOf(expected);
        Pattern beanMethod = Pattern.compile("@Bean\\s+(?:public\\s+)?EntityDefinition\\s+\\w+\\(");
        long inSources;
        try (Stream<Path> tree = Files.walk(Path.of("src/main/java"))) {
            inSources = tree.filter(path -> path.toString().endsWith(".java"))
                    .mapToLong(path -> beanMethod.matcher(read(path)).results().count())
                    .sum();
        }
        assertThat(entities).hasSize((int) inSources);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The entities the application declares: every {@code @Bean EntityDefinition} method of its configurations,
     * whatever it takes. A declaration may take the beans its custom scope reads (the users', tasks' and projects'
     * scope): they are asked only when a viewer's rows are read, never while the entity is declared, so the
     * declaration is built here with {@code null} in their place.
     */
    static List<EntityDefinition> declaredEntities() throws Exception {
        return declarations().stream().map(Declaration::entity).toList();
    }

    /** A declared entity and the configuration class that declares it. */
    public record Declaration(EntityDefinition entity, Class<?> type) {}

    /** The declarations of {@link #declaredEntities()} with their configuration classes. */
    public static List<Declaration> declarations() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        // The application's own declarations, not the fixtures of tests (EntityFieldRightsIntegrationTest).
        scanner.addExcludeFilter(new TestFixtureExcludeFilter());
        List<Declaration> declarations = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.smartup24.cms.instance")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class) && method.getReturnType() == EntityDefinition.class) {
                    assertThat(method.getParameterTypes())
                            .as("%s.%s takes only beans", type.getSimpleName(), method.getName())
                            .noneMatch(Class::isPrimitive);
                    method.setAccessible(true);
                    var entity =
                            (EntityDefinition) method.invoke(instance(type), new Object[method.getParameterCount()]);
                    declarations.add(new Declaration(entity, type));
                }
            }
        }
        return declarations;
    }

    private static Object instance(Class<?> type) throws Exception {
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(new Object[ctor.getParameterCount()]);
    }
}
