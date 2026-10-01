package com.smartup24.cms.instance.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class of integration tests: an embedded PostgreSQL with two databases ({@link TestDatabases}), migrations
 * applied.
 *
 * <p>Assumption: the requirements call for Testcontainers, but Docker is not available in the development
 * environment (no administrator rights to install it). {@code embedded-postgres} is used instead: the same real
 * PostgreSQL, started as a process. The test contract does not change; once Docker is available, switching back
 * takes an edit to one class.
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
        // The second database (pg-dwh) lives in the same embedded PostgreSQL
        registry.add("app.dwh.url", () -> TestDatabases.jdbcUrl(TestDatabases.DWH_DB));
        registry.add("app.dwh.username", () -> TestDatabases.USER);
        registry.add("app.dwh.password", () -> "");
        // Test contexts run on stub channels; KauthDeliveryGuardTest covers the guard itself.
        registry.add("smc.delivery.enforce", () -> "false");
        // Stored secrets are encrypted with an installation key (ADR-0029); tests share one derived key.
        registry.add("smc.secrets.key", TestStoredSecrets::keyBase64);
        registry.add("app.dwh.connect-timeout", () -> "2s");
        // Assumption: in tests OneID is a mock provider without network access; each test turns it on explicitly
        registry.add("platform.oneid.mock", () -> true);
        // The framework requires the instance code and name on first start (FR-INST-1); tests use synthetic ones
        registry.add("dwh.instance.client-code", () -> "TEST-INSTANCE");
        registry.add("dwh.instance.client-name", () -> "TEST instance");
        // The generated bootstrap password is written to a file; in tests, to a temporary directory
        // Files of the framework's mf module are kept on disk in the test's temporary directory, not in ./data/storage
        registry.add("dwh.storage.local-path", () -> System.getProperty("java.io.tmpdir") + "/dwh-test-storage");
        registry.add(
                "platform.bootstrap.admin-password-file",
                () -> System.getProperty("java.io.tmpdir") + "/dwh-test-bootstrap-password.txt");
        // The job queue ticker is off in tests: the tests drain the queue themselves by calling runQueued()
        registry.add("dwh.fnd.jobs.ticker-enabled", () -> false);
    }
}
