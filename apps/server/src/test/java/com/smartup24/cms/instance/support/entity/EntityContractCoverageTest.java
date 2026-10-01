package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * "The kit is attached to 100% of entities" (ADR-0032, 11.4; plan 10/10, item 6.2): every entity the application
 * declares with a table has exactly one {@link EntityContractTestKit} subclass in the tests, and every subclass names
 * such an entity. A new entity without its contract test fails the build here.
 */
class EntityContractCoverageTest extends EmbeddedPostgresTest {

    @Autowired
    private List<EntityDefinition> declared;

    @Test
    @DisplayName("6.2: every entity with a table has exactly one contract test, and every contract test an entity")
    void everyEntityWithATableHasOneContractTest() throws Exception {
        Set<String> entities = declared.stream()
                .filter(entity -> entity.model() != null)
                .map(EntityDefinition::code)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(entities)
                .as("the application declares entities with a table")
                .isNotEmpty();
        assertThat(problems(entities, kits())).isEmpty();
    }

    @Test
    @DisplayName("6.2: an entity without a contract test, a second one and one of no entity are each reported")
    void theGapsAreReported() {
        Map<String, List<String>> kits = Map.of(
                "ms.notes", List.of("MsNoteContractTest"),
                "ms.twice", List.of("FirstContractTest", "SecondContractTest"),
                "ms.gone", List.of("StaleContractTest"));

        assertThat(problems(Set.of("ms.notes", "ms.twice", "ms.orders"), kits))
                .containsExactlyInAnyOrder(
                        "ms.orders has no EntityContractTestKit subclass",
                        "ms.twice has more than one contract test: [FirstContractTest, SecondContractTest]",
                        "StaleContractTest names ms.gone, which is no entity with a table");
    }

    /** What keeps the entities and their contract tests from matching one to one. */
    static List<String> problems(Set<String> entities, Map<String, ? extends Collection<String>> kits) {
        List<String> problems = new ArrayList<>();
        for (String entity : new TreeSet<>(entities)) {
            Collection<String> tests = kits.get(entity);
            if (tests == null || tests.isEmpty()) {
                problems.add(entity + " has no EntityContractTestKit subclass");
            } else if (tests.size() > 1) {
                problems.add(entity + " has more than one contract test: " + new TreeSet<>(tests));
            }
        }
        new TreeMap<>(kits).forEach((entity, tests) -> {
            if (!entities.contains(entity)) {
                tests.forEach(test -> problems.add(test + " names " + entity + ", which is no entity with a table"));
            }
        });
        return problems;
    }

    /** The concrete subclasses of the kit on the test class path, by the entity each names. */
    private static Map<String, List<String>> kits() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(EntityContractTestKit.class));
        Map<String, List<String>> kits = new TreeMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.smartup24.cms")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            EntityContractTestKit kit = (EntityContractTestKit) constructor.newInstance();
            kits.computeIfAbsent(kit.entity(), code -> new ArrayList<>()).add(type.getSimpleName());
        }
        return kits;
    }
}
