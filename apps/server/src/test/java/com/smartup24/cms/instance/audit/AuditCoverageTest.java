package com.smartup24.cms.instance.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.ms.task.service.MsTaskAuditTrail;
import com.smartup24.cms.instance.search.service.SearchJobAudit;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-AUD-1: a significant change leaves a trace.
 *
 * A review on 29.08 showed that only three services out of about fifteen wrote audit records: permission grants,
 * files, webhooks and dynamic fields were not logged at all. The gap opened silently: no test checked coverage,
 * because checking "every call" is impossible.
 *
 * So the test checks what can be checked and what catches a regression: a service with a mutating transaction
 * must depend on {@link AuditLogService}. This does not guarantee a call in every branch, but it guarantees that
 * a new mutating service never appears without audit at all.
 */
class AuditCoverageTest {

    /**
     * Search jobs write fixed-field, explicit-actor audit rows through {@link SearchJobAudit}, which hands them to
     * {@link AuditLogService} (ADR-0026). The worker also uses it on the activation connection, preserving
     * pointer/job/audit atomicity. This is audited delegation, not an exemption; behavioral coverage lives in the job
     * tests.
     */
    private static final Map<String, Class<?>> AUDIT_DELEGATES = Map.of(
            "SearchJobService",
            SearchJobAudit.class,
            // Plan 10/10, item 3.10: the files of a task are written to its ms_tasks entries through one trail. The
            // tasks, projects, task types and statuses are entities the general runtime audits (ADR-0032, 6.8).
            "MsTaskFileService",
            MsTaskAuditTrail.class);

    /**
     * Services without audit, each with a reason. The list is closed: a new service is added only with a reason why
     * its mutations are not significant.
     */
    private static final Set<String> WITHOUT_AUDIT_BY_DESIGN = Set.of(
            "AuditLogService", // the log mechanism itself
            "MdPermissionService", // materializes permissions; the callers log the change that caused it
            "IdempotencyService", // a service cache of responses, does not change business state
            "KauthSessionService", // sign-in and sign-out go to security_events, not to audit_log
            "KauthApiTokenService", // issuing and revoking a token go to security_events too
            "SearchService", // indexing, derived from data that is already logged
            "SearchChangePublisher", // derived revisions; the owner logs the business mutation
            "MsNotificationService", // delivers notifications, does not change data
            // the participation rows follow the task record the runtime audits; a "seen" mark is no business change
            "MsTaskMemberService",
            // audited by the foundation triggers (fnd_audit_enable, V100): the actor comes from app.user_id, one
            // audit_log row per insert, update or delete
            "WarehouseLoadService",
            "UnitService",
            "VersioningService",
            "UplSourceService",
            "UplPackageService");

    @Test
    @DisplayName("Каждый мутирующий сервис зависит от AuditLogService")
    void everyMutatingServiceDependsOnAudit() {
        List<String> withoutAudit = new ArrayList<>();

        for (Class<?> service : findServices()) {
            if (WITHOUT_AUDIT_BY_DESIGN.contains(service.getSimpleName()) || !hasMutatingTransaction(service)) {
                continue;
            }
            if (!dependsOnAudit(service)) {
                withoutAudit.add(service.getSimpleName());
            }
        }

        assertThat(withoutAudit)
                .as("Мутирующие сервисы без AuditLogService (FR-AUD-1): %s", withoutAudit)
                .isEmpty();
    }

    @Test
    @DisplayName("Список исключений не протух: каждое имя из него существует")
    void exclusionListHasNoStaleEntries() {
        Set<String> existing = new TreeSet<>();
        findServices().forEach(c -> existing.add(c.getSimpleName()));
        existing.add("AuditLogService");

        List<String> stale = WITHOUT_AUDIT_BY_DESIGN.stream()
                .filter(name -> !existing.contains(name))
                .sorted()
                .toList();

        assertThat(stale)
                .as("Исключения для несуществующих сервисов — список пора чистить: %s", stale)
                .isEmpty();
    }

    @Test
    void delegatedAuditMappingsReferToRealServicesAndConstructorDependencies() {
        List<Class<?>> services = findServices();
        AUDIT_DELEGATES.forEach((name, dependency) -> {
            List<Class<?>> matches = services.stream()
                    .filter(service -> service.getSimpleName().equals(name))
                    .toList();
            assertThat(matches).as("Mapped audited service %s", name).hasSize(1);
            Class<?> service = matches.getFirst();
            assertThat(hasMutatingTransaction(service))
                    .as("Mapped mutating service %s", name)
                    .isTrue();
            assertThat(WITHOUT_AUDIT_BY_DESIGN).doesNotContain(name);
            assertThat(Arrays.stream(service.getDeclaredConstructors())
                            .anyMatch(ctor ->
                                    Arrays.asList(ctor.getParameterTypes()).contains(dependency)))
                    .as("Mapped audit constructor dependency %s -> %s", name, dependency.getSimpleName())
                    .isTrue();
        });
    }

    private static boolean hasMutatingTransaction(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            Transactional tx = m.getAnnotation(Transactional.class);
            if (tx != null && !tx.readOnly()) {
                return true;
            }
        }
        return false;
    }

    private static boolean dependsOnAudit(Class<?> type) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            List<Class<?>> dependencies = Arrays.asList(ctor.getParameterTypes());
            Class<?> delegate = AUDIT_DELEGATES.get(type.getSimpleName());
            if (dependencies.contains(AuditLogService.class) || (delegate != null && dependencies.contains(delegate))) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> findServices() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        List<Class<?>> services = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.smartup24.cms.instance")) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                var source = type.getProtectionDomain().getCodeSource();
                if (source != null && source.getLocation().getPath().contains("test-classes")) {
                    continue;
                }
                services.add(type);
            } catch (ClassNotFoundException ignored) {
                // the class is not on this classpath: nothing to check
            }
        }
        return services;
    }
}
