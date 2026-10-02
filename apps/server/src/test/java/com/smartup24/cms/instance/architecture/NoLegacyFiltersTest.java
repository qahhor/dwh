package com.smartup24.cms.instance.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0032, 8 (plan 10/10, item 5.6, acceptance "{@code Legacy*Filters} = 0"): a list is filtered by the filter
 * expression of ADR-0016 only. No class keeps the filter parameters of an old list ({@code Legacy*Filters}, nested
 * classes included, nothing pending: the users and the tasks are both on the model), and the task module's own
 * endpoints take no list parameter beside paging.
 */
class NoLegacyFiltersTest {

    private static final String ROOT = "com.smartup24.cms";
    private static final String TASK_MODULE = ROOT + ".instance.ms.task";
    private static final Pattern LEGACY = Pattern.compile("Legacy\\w*Filters");

    /** What a module endpoint of the tasks may take as a query parameter: a page, or the records it names. */
    private static final Set<String> TASK_PARAMETERS = Set.of("limit", "cursor", "ids");

    private static JavaClasses main;

    @BeforeAll
    static void importClasses() {
        main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("ADR-0032, 8: no Legacy*Filters class is left beside the filter expression")
    void noLegacyFilterClasses() {
        List<String> legacy = main.stream()
                .filter(type -> LEGACY.matcher(type.getSimpleName()).matches())
                .map(JavaClass::getName)
                .sorted()
                .toList();
        assertThat(legacy)
                .as("old list filters: filter by the expression of ADR-0016")
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0032, 8: the task module's endpoints take only paging and record ids as query parameters")
    void taskEndpointsTakeNoListFilters() throws ClassNotFoundException {
        List<String> offending = new ArrayList<>();
        for (JavaClass type : main) {
            if (!type.getPackageName().startsWith(TASK_MODULE) || !type.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (Method method : Class.forName(type.getName()).getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    RequestParam param = parameter.getAnnotation(RequestParam.class);
                    if (param != null && !TASK_PARAMETERS.contains(param.name())) {
                        offending.add(type.getSimpleName() + "#" + method.getName() + "(" + param.name() + ")");
                    }
                }
            }
        }
        assertThat(offending)
                .as("list parameters outside the filter expression")
                .isEmpty();
    }
}
