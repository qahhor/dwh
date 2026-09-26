package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.common.entity.EntityDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every action an entity offers (ADR-0019, 2.2) is a right an endpoint really checks: {@code form-meta} shows a
 * button by that right, so a right no endpoint declares would show a button the server then refuses, or hide one
 * it allows.
 */
class EntityActionPermissionContractTest {

    @Test
    void everyEntityActionAndViewIsADeclaredPermission() throws Exception {
        Set<String> declared = MdFormCatalogTest.declaredPairsFromSources();
        List<EntityDefinition> entities = declaredEntities();
        assertThat(entities).extracting(EntityDefinition::code).contains("ms.notes");

        List<String> missing = new ArrayList<>();
        for (EntityDefinition entity : entities) {
            if (!declared.contains(entity.form() + ".view")) missing.add(entity.code() + " view");
            for (EntityDefinition.EntityAction action : entity.actions()) {
                if (!declared.contains(entity.form() + "." + action.permission())) {
                    missing.add(entity.code() + " " + action.code() + " → " + entity.form() + "." + action.permission());
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    /** The entities the application declares: the {@code @Bean EntityDefinition} methods of its configurations. */
    private static List<EntityDefinition> declaredEntities() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        List<EntityDefinition> entities = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.greenwhite.dwh.instance")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Bean.class) && method.getReturnType() == EntityDefinition.class
                        && method.getParameterCount() == 0) {
                    var ctor = type.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    method.setAccessible(true);
                    entities.add((EntityDefinition) method.invoke(ctor.newInstance()));
                }
            }
        }
        return entities;
    }
}
