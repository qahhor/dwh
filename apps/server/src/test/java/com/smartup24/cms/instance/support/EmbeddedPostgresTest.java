package com.smartup24.cms.instance.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * База интеграционных тестов: встроенный PostgreSQL с двумя базами ({@link TestDatabases}), миграции применены.
 *
 * <p>[допущение] Промпт 07 п.5 требует Testcontainers, но Docker в среде разработки недоступен
 * (нет прав администратора для установки). Взят {@code embedded-postgres} — тот же настоящий
 * PostgreSQL, запускаемый как процесс. Контракт тестов не меняется; при появлении Docker
 * замена обратима правкой одного класса.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TypeExcludeFilters(TestFixtureExcludeFilter.class)
public abstract class EmbeddedPostgresTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabases.migrateOnce();
        registry.add("spring.datasource.url", () -> TestDatabases.jdbcUrl(TestDatabases.OLTP_DB));
        registry.add("spring.datasource.username", () -> TestDatabases.USER);
        registry.add("spring.datasource.password", () -> "");
        // Вторая БД (pg-dwh) — в том же встроенном PostgreSQL (DoD AC-1)
        registry.add("app.dwh.url", () -> TestDatabases.jdbcUrl(TestDatabases.DWH_DB));
        registry.add("app.dwh.username", () -> TestDatabases.USER);
        registry.add("app.dwh.password", () -> "");
        // Test contexts run on stub channels; KauthDeliveryGuardTest covers the guard itself.
        registry.add("smc.delivery.enforce", () -> "false");
        registry.add("app.dwh.connect-timeout", () -> "2s");
        // OneID в тестах — mock-провайдер без сети (AC [допущение 10]); включается каждым тестом явно
        registry.add("platform.oneid.mock", () -> true);
        // Каркас требует код и имя экземпляра при первом старте (FR-INST-1); в тестах — синтетические
        registry.add("dwh.instance.client-code", () -> "TEST-INSTANCE");
        registry.add("dwh.instance.client-name", () -> "TEST instance");
        // Сгенерированный пароль bootstrap пишется в файл (AC-1/AC-17) — в тестах во временный каталог
        // Файлы модуля mf каркаса (AC-8) — на диске во временном каталоге теста, не в ./data/storage
        registry.add("dwh.storage.local-path", () -> System.getProperty("java.io.tmpdir") + "/dwh-test-storage");
        registry.add(
                "platform.bootstrap.admin-password-file",
                () -> System.getProperty("java.io.tmpdir") + "/dwh-test-bootstrap-password.txt");
        // Запускатель очереди заданий в тестах выключен: очередь снимают сами тесты вызовом runQueued()
        registry.add("dwh.fnd.jobs.ticker-enabled", () -> false);
    }
}
