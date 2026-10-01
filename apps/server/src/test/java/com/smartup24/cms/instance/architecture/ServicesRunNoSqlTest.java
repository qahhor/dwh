package com.smartup24.cms.instance.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.sql.Connection;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 10/10, item 4.2: a service holds the rules, its repository holds the SQL. No class named {@code *Service}, nor a
 * class nested in one, writes SQL: it calls no {@code JdbcClient.sql}, no method of {@code JdbcTemplate} or
 * {@code NamedParameterJdbcTemplate}, and prepares no statement on a {@code Connection}. Strict for the whole
 * application, without a frozen list.
 */
class ServicesRunNoSqlTest {

    private static final String ROOT = "com.smartup24.cms.instance";
    /** The methods of {@link Connection} that run SQL text; transaction control is allowed. */
    private static final Set<String> CONNECTION_SQL = Set.of("prepareStatement", "prepareCall", "createStatement");

    private static JavaClasses main;

    @BeforeAll
    static void importClasses() {
        main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    /** A class named {@code *Service} or nested in one. */
    static final DescribedPredicate<JavaClass> SERVICE_OR_NESTED =
            DescribedPredicate.describe("a *Service class or a class nested in one", javaClass -> {
                for (JavaClass type = javaClass;
                        type != null;
                        type = type.getEnclosingClass().orElse(null)) {
                    if (type.getSimpleName().endsWith("Service")) {
                        return true;
                    }
                }
                return false;
            });

    static ArchRule servicesRunNoSqlRule() {
        return classes()
                .that(SERVICE_OR_NESTED)
                .should(new ArchCondition<>("run no SQL (the module's repository does)") {
                    @Override
                    public void check(JavaClass service, ConditionEvents events) {
                        for (JavaMethodCall call : service.getMethodCallsFromSelf()) {
                            if (runsSql(call)) {
                                events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                            }
                        }
                    }
                })
                .as("no *Service class runs SQL (plan 10/10, item 4.2)");
    }

    private static boolean runsSql(JavaMethodCall call) {
        JavaClass owner = call.getTargetOwner();
        String method = call.getName();
        if (owner.isEquivalentTo(JdbcClient.class)) {
            return method.equals("sql");
        }
        if (owner.isAssignableTo(JdbcOperations.class) || owner.isAssignableTo(NamedParameterJdbcOperations.class)) {
            return true;
        }
        return owner.isAssignableTo(Connection.class) && CONNECTION_SQL.contains(method);
    }

    @Test
    @DisplayName("4.2: no *Service class calls jdbc.sql, JdbcTemplate or prepares a statement")
    void servicesRunNoSql() {
        servicesRunNoSqlRule().check(main);
    }

    @Test
    @DisplayName("4.2: a service with SQL, and a class nested in one, make the rule red")
    void sqlInServiceIsFound() {
        EvaluationResult result = servicesRunNoSqlRule()
                .evaluate(new ClassFileImporter().importClasses(ProbeService.class, ProbeService.Step.class));
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("ProbeService.count"))
                .anyMatch(detail -> detail.contains("ProbeService$Step.run"));
    }

    /** A service that queries by itself, the kind of class the rule forbids. */
    static final class ProbeService {
        private final JdbcClient jdbc;

        ProbeService(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        long count() {
            return jdbc.sql("select count(*) from md_users").query(Long.class).single();
        }

        /** A class nested in a service is part of it. */
        static final class Step {
            void run(JdbcOperations operations) {
                operations.execute("select 1");
            }
        }
    }
}
