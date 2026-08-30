package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.md.pref.MdFormCatalog;
import com.greenwhite.dwh.instance.md.service.MdFormCatalogSynchronizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-PERM-1: каталог форм — производная от кода, а не наоборот.
 *
 * Здесь проверяется половина, которой не нужна база: сканер аннотаций и полнота
 * человекочитаемых имён. Без имени пара всё равно попадёт в каталог (под своим
 * кодом), но администратор увидит в матрице прав «tasks.items.create» вместо
 * «Создание задачи» — поэтому пропуск валит сборку, а не деградирует молча.
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
    @DisplayName("У каждой объявленной в коде пары есть человеческое имя в справочнике")
    void everyDeclaredPermissionHasHumanName() {
        List<String> withoutName = new ArrayList<>();
        for (String pair : declaredPairsFromSources()) {
            int dot = pair.lastIndexOf('.');
            String form = pair.substring(0, dot);
            String action = pair.substring(dot + 1);
            if (!MdFormCatalog.hasHumanName(form, action)) {
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

    /** Пары из всех контроллеров приложения — тот же набор, что соберёт синхронизатор при старте. */
    static Set<String> declaredPairsFromSources() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> pairs = new TreeSet<>();
        for (var definition : scanner.findCandidateComponents("com.greenwhite.dwh.instance")) {
            try {
                Class<?> controller = Class.forName(definition.getBeanClassName());
                if (isFromTestClasspath(controller)) {
                    // Тестовые стенды (SecurityTestController и подобные) охраняют
                    // выдуманные формы — в каталоге приложения им делать нечего.
                    continue;
                }
                for (Method m : controller.getDeclaredMethods()) {
                    RequiresPermission rp = m.getAnnotation(RequiresPermission.class);
                    if (rp != null) {
                        pairs.add(rp.form() + "." + rp.action());
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // Контроллер с тестового classpath — в каталоге ему делать нечего.
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
