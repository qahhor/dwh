package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-AUD-1: значимое изменение оставляет след.
 *
 * Ревизия 29.08 показала, что аудит писали три сервиса из полутора десятков —
 * выдача прав, файлы, вебхуки и динамические поля не журналировались вовсе.
 * Разошлось это молча: ни один тест не проверял покрытие, потому что проверять
 * «каждый вызов» невозможно.
 *
 * Проверяем то, что проверяемо и что ловит регресс: сервис, у которого есть
 * мутирующая транзакция, обязан зависеть от {@link AuditLogService}. Это не
 * гарантирует, что вызов расставлен в каждой ветке, но гарантирует, что новый
 * мутирующий сервис не появится вообще без аудита.
 */
class AuditCoverageTest {

    /**
     * Сервисы без аудита — каждый с обоснованием. Список закрытый: новый сервис
     * сюда не добавляется без причины, по которой его мутации не значимы.
     */
    private static final Set<String> WITHOUT_AUDIT_BY_DESIGN = Set.of(
            "AuditLogService",        // сам механизм журнала
            "MdPermissionService",    // материализация прав; источник изменения журналируют вызывающие
            "IdempotencyService",     // служебный кэш ответов, бизнес-состояния не меняет
            "KauthSessionService",    // вход и выход пишутся в security_events, а не в audit_log
            "KauthApiTokenService",   // выдача и отзыв токена — тоже security_events
            "SearchService",          // индексация, производная от уже пожурналированных данных
            "TypesenseIndexer",       // то же самое
            "MsNotificationService"   // доставка оповещений, а не изменение данных
    );

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
            if (Arrays.asList(ctor.getParameterTypes()).contains(AuditLogService.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> findServices() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        List<Class<?>> services = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("com.greenwhite.dwh.instance")) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                var source = type.getProtectionDomain().getCodeSource();
                if (source != null && source.getLocation().getPath().contains("test-classes")) {
                    continue;
                }
                services.add(type);
            } catch (ClassNotFoundException ignored) {
                // класса нет на этом classpath — проверять нечего
            }
        }
        return services;
    }
}
