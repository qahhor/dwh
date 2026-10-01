package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.md.pref.MdFormCatalog;
import com.smartup24.cms.instance.md.service.MdFormCatalogSynchronizer;
import com.smartup24.cms.instance.md.service.MdPermissionService;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * FR-PERM-1: the form catalog derives from the code, not the other way round.
 *
 * This class checks the half that needs no database: the annotation scanner and the completeness of
 * human-readable names. A pair without a name still gets into the catalog (under its code), but an
 * administrator would see "tasks.items.create" in the permission matrix instead of a readable action
 * name, so a missing name fails the build instead of degrading silently.
 */
class MdFormCatalogTest {

    @Test
    @DisplayName("Сканер собирает пары из аннотаций обработчиков")
    void scannerCollectsPairsFromHandlers() throws Exception {
        var bean = new SampleController();
        List<HandlerMethod> handlers = List.of(
                new HandlerMethod(bean, SampleController.class.getDeclaredMethod("read")),
                new HandlerMethod(bean, SampleController.class.getDeclaredMethod("write")),
                new HandlerMethod(bean, SampleController.class.getDeclaredMethod("unprotected")));

        assertThat(MdFormCatalogSynchronizer.declaredPairs(handlers))
                .containsExactly("sample.form.create", "sample.form.view");
    }

    @Test
    @DisplayName("Профиль миграций не поднимает синхронизатор, которому нужен web-контекст")
    void migrationProfileDoesNotCreateWebCatalogSynchronizer() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("migrate");
            context.registerBean(RequestMappingHandlerMapping.class, () -> mock(RequestMappingHandlerMapping.class));
            context.registerBean(MdPermissionService.class, () -> mock(MdPermissionService.class));
            context.register(MdFormCatalogSynchronizer.class);
            context.refresh();

            assertThat(context.getBeansOfType(MdFormCatalogSynchronizer.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("У каждой объявленной в коде пары есть человеческое имя в справочнике")
    void everyDeclaredPermissionHasHumanName() throws Exception {
        // A declared entity names its own right (EntityRights, roadmap item 57).
        Set<String> namedByEntities = new TreeSet<>();
        for (var entity : EntityActionPermissionContractTest.declaredEntities()) {
            if (entity.rights() != null) {
                entity.rights()
                        .actionNames()
                        .keySet()
                        .forEach(action -> namedByEntities.add(entity.form() + "." + action));
            }
        }
        List<String> withoutName = new ArrayList<>();
        for (String pair : declaredPairsFromSources()) {
            int dot = pair.lastIndexOf('.');
            String form = pair.substring(0, dot);
            String action = pair.substring(dot + 1);
            if (!MdFormCatalog.hasHumanName(form, action) && !namedByEntities.contains(pair)) {
                withoutName.add(pair);
            }
        }
        assertThat(withoutName)
                .as("Пары без имени в MdFormCatalog — в матрице прав будут показаны кодом: %s", withoutName)
                .isEmpty();
    }

    @Test
    @DisplayName("Незнакомая форма не роняет каталог: имя и модуль выводятся из кода")
    void unknownFormDegradesGracefully() {
        assertThat(MdFormCatalog.formNameOf("unknown.form")).isEqualTo("unknown.form");
        assertThat(MdFormCatalog.actionNameOf("unknown.form", "view")).isEqualTo("view");
        assertThat(MdFormCatalog.moduleOf("unknown.form")).isEqualTo("unknown");
    }

    /** The pairs of all application controllers: the same set the synchronizer collects at start. */
    static Set<String> declaredPairsFromSources() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> pairs = new TreeSet<>();
        for (var definition : scanner.findCandidateComponents("com.smartup24.cms.instance")) {
            try {
                Class<?> controller = Class.forName(definition.getBeanClassName());
                if (isFromTestClasspath(controller)) {
                    // Test stands (SecurityTestController and the like) guard
                    // made-up forms: they have no place in the application catalog.
                    continue;
                }
                for (Method m : controller.getDeclaredMethods()) {
                    RequiresPermission rp = m.getAnnotation(RequiresPermission.class);
                    if (rp != null) {
                        pairs.add(rp.form() + "." + rp.action());
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // A controller from the test classpath has no place in the catalog.
            }
        }
        return pairs;
    }

    private static boolean isFromTestClasspath(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source != null && source.getLocation().getPath().contains("test-classes");
    }

    @RestController
    static class SampleController {
        @GetMapping("/a")
        @RequiresPermission(form = "sample.form", action = "view")
        String read() {
            return "ok";
        }

        @GetMapping("/b")
        @RequiresPermission(form = "sample.form", action = "create")
        String write() {
            return "ok";
        }

        @GetMapping("/c")
        String unprotected() {
            return "ok";
        }
    }
}
