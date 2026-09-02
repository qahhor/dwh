package com.greenwhite.dwh.instance.ms.notify;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.ms.notify.controller.MsAnnouncementAdminController;
import com.greenwhite.dwh.instance.ms.notify.model.AnnouncementDraftRequest;
import com.greenwhite.dwh.instance.ms.notify.model.AnnouncementState;
import com.greenwhite.dwh.instance.ms.notify.pref.MsNotifyPref;
import com.greenwhite.dwh.instance.ms.notify.repository.MsAnnouncementRepository;
import com.greenwhite.dwh.instance.ms.notify.service.MsAnnouncementService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MsAnnouncementServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("smartupcms_announcements_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MsAnnouncementRepository repository;
    static MsAnnouncementService service;
    static Long authorId;

    @BeforeAll
    static void setupDatabase() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbc = JdbcClient.create(dataSource);
        authorId = jdbc.sql("""
                        insert into md_users (name, login, email)
                        values ('Announcement Admin', 'announcement-admin', 'announcement-admin@example.test')
                        returning id
                        """)
                .query(Long.class)
                .single();

        ObjectMapper objectMapper = new ObjectMapper();
        repository = new MsAnnouncementRepository(jdbc, objectMapper);
        AuditLogService audit = new AuditLogService(
                new AuditLogRepository(jdbc, objectMapper), null);
        service = new MsAnnouncementService(repository, audit);
    }

    @BeforeEach
    void authenticate() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                authorId,
                "announcement-admin",
                "announcement-admin@example.test",
                77L,
                false,
                Set.of("*.*"),
                1L));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void lifecyclePreservesLocalizedContentRejectsStaleWritesAndWritesAudit() {
        var created = service.create(draft(
                Map.of("ru", "Плановые работы", "en", "Maintenance window"),
                Map.of("ru", "Сегодня в 22:00", "en", "Today at 22:00"),
                "WARNING",
                null), authorId);

        assertThat(created.state()).isEqualTo(AnnouncementState.DRAFT);
        assertThat(created.titleJson()).containsEntry("ru", "Плановые работы")
                .containsEntry("en", "Maintenance window");
        assertThat(created.bodyJson()).containsEntry("ru", "Сегодня в 22:00")
                .containsEntry("en", "Today at 22:00");
        assertThat(created.lockVersion()).isZero();
        assertThat(created.createdBy()).isEqualTo(authorId);

        var updated = service.update(created.id(), draft(
                Map.of("ru", "Работы перенесены", "en", "Maintenance rescheduled"),
                Map.of("ru", "Сегодня в 23:00", "en", "Today at 23:00"),
                "CRITICAL",
                created.lockVersion()));
        assertThat(updated.lockVersion()).isEqualTo(1L);
        assertThat(updated.titleJson()).containsEntry("en", "Maintenance rescheduled");

        assertThatThrownBy(() -> service.update(created.id(), draft(
                Map.of("ru", "Устаревшая правка"),
                Map.of("ru", "Не должна сохраниться"),
                "INFO",
                created.lockVersion())))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode().getDefaultStatus())
                        .isEqualTo(409));

        var published = service.publish(created.id(), updated.lockVersion());
        assertThat(published.state()).isEqualTo(AnnouncementState.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();
        assertThat(repository.getActiveUnreadAnnouncements(authorId, "en"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(created.id());
                    assertThat(item.title()).isEqualTo("Maintenance rescheduled");
                    assertThat(item.body()).isEqualTo("Today at 23:00");
                });

        assertThatThrownBy(() -> service.publish(created.id(), published.lockVersion()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode().getDefaultStatus())
                        .isEqualTo(409));

        var archived = service.archive(created.id(), published.lockVersion());
        assertThat(archived.state()).isEqualTo(AnnouncementState.ARCHIVED);
        assertThat(archived.archivedAt()).isNotNull();
        assertThat(repository.getActiveUnreadAnnouncements(authorId, "ru")).isEmpty();

        assertThat(jdbc.sql("""
                        select event
                        from audit_log
                        where table_name = 'ms_announcements' and row_pk = :id
                        order by id
                        """)
                .param("id", String.valueOf(created.id()))
                .query(String.class)
                .list())
                .containsExactly("I", "U", "U", "U");
        assertThat(jdbc.sql("""
                        select new_row ->> 'state'
                        from audit_log
                        where table_name = 'ms_announcements' and row_pk = :id
                        order by id desc
                        limit 1
                        """)
                .param("id", String.valueOf(created.id()))
                .query(String.class)
                .single())
                .isEqualTo("ARCHIVED");
    }

    @Test
    void rejectsInvalidLocalizedContentAndBannerType() {
        assertThatThrownBy(() -> service.create(draft(
                Map.of("en", "No Russian title"),
                Map.of("ru", "Текст"),
                "INFO",
                null), authorId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("RU");

        assertThatThrownBy(() -> service.create(draft(
                Map.of("ru", "Заголовок"),
                Map.of("ru", "Текст"),
                "HTML",
                null), authorId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("INFO");

        assertThatThrownBy(() -> service.create(draft(
                Map.of("ru", "Заголовок"),
                Map.of("ru", "x".repeat(10_001)),
                "INFO",
                null), authorId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10000");
    }

    @Test
    void adminControllerUsesPermissionSpecificActions() throws Exception {
        assertPermission("manage", "update");
        assertPermission("create", "create", AnnouncementDraftRequest.class);
        assertPermission("update", "update", Long.class, AnnouncementDraftRequest.class);
        assertPermission("publish", "publish", Long.class, MsAnnouncementAdminController.VersionRequest.class);
        assertPermission("archive", "archive", Long.class, MsAnnouncementAdminController.VersionRequest.class);
    }

    @Test
    void managementReadSurvivesNullValuesPreservedFromLegacyJson() {
        Long id = jdbc.sql("""
                        insert into ms_announcements
                            (title_json, body_json, banner_type, state, created_by)
                        values
                            ('{"ru": null}'::jsonb, '{"ru": "Legacy body"}'::jsonb,
                             'INFO', 'DRAFT', :createdBy)
                        returning id
                        """)
                .param("createdBy", authorId)
                .query(Long.class)
                .single();

        assertThat(repository.findById(id))
                .get()
                .satisfies(announcement -> assertThat(announcement.titleJson())
                        .containsEntry("ru", null));
    }

    private static void assertPermission(String method, String action, Class<?>... parameterTypes)
            throws Exception {
        RequiresPermission permission = MsAnnouncementAdminController.class
                .getMethod(method, parameterTypes)
                .getAnnotation(RequiresPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.form()).isEqualTo(MsNotifyPref.FORM_ANNOUNCEMENTS);
        assertThat(permission.action()).isEqualTo(action);
    }

    private static AnnouncementDraftRequest draft(
            Map<String, String> title,
            Map<String, String> body,
            String bannerType,
            Long lockVersion) {
        return new AnnouncementDraftRequest(title, body, bannerType, lockVersion);
    }
}
