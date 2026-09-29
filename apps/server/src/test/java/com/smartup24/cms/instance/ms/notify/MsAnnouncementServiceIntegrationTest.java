package com.smartup24.cms.instance.ms.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.audit.repository.AuditLogRepository;
import com.smartup24.cms.instance.audit.service.AuditDataRedactor;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementDraftRequest;
import com.smartup24.cms.instance.ms.notify.api.AnnouncementVersionRequest;
import com.smartup24.cms.instance.ms.notify.controller.MsAnnouncementAdminController;
import com.smartup24.cms.instance.ms.notify.model.AnnouncementState;
import com.smartup24.cms.instance.ms.notify.pref.MsNotifyPref;
import com.smartup24.cms.instance.ms.notify.repository.MsAnnouncementRepository;
import com.smartup24.cms.instance.ms.notify.service.MsAnnouncementService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class MsAnnouncementServiceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("smartupcms_announcements_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MsAnnouncementRepository repository;
    static MsAnnouncementService service;
    static Long authorId;

    @BeforeAll
    static void setupDatabase() {
        var dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
                        """).query(Long.class).single();

        ObjectMapper objectMapper = new ObjectMapper();
        repository = new MsAnnouncementRepository(jdbc, objectMapper);
        AuditLogService audit =
                new AuditLogService(new AuditLogRepository(jdbc, objectMapper), null, new AuditDataRedactor());
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
                1L,
                false,
                0,
                null));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void lifecyclePreservesLocalizedContentRejectsStaleWritesAndWritesAudit() {
        var created = service.create(
                draft(
                        Map.of("ru", "Плановые работы", "en", "Maintenance window"),
                        Map.of("ru", "Сегодня в 22:00", "en", "Today at 22:00"),
                        "WARNING",
                        null),
                authorId);

        assertThat(created.state()).isEqualTo(AnnouncementState.DRAFT);
        assertThat(created.titleJson())
                .containsEntry("ru", "Плановые работы")
                .containsEntry("en", "Maintenance window");
        assertThat(created.bodyJson()).containsEntry("ru", "Сегодня в 22:00").containsEntry("en", "Today at 22:00");
        assertThat(created.lockVersion()).isZero();
        assertThat(created.createdBy()).isEqualTo(authorId);

        var updated = service.update(
                created.id(),
                draft(
                        Map.of("ru", "Работы перенесены", "en", "Maintenance rescheduled"),
                        Map.of("ru", "Сегодня в 23:00", "en", "Today at 23:00"),
                        "CRITICAL",
                        created.lockVersion()));
        assertThat(updated.lockVersion()).isEqualTo(1L);
        assertThat(updated.titleJson()).containsEntry("en", "Maintenance rescheduled");

        assertThatThrownBy(() -> service.update(
                        created.id(),
                        draft(
                                Map.of("ru", "Устаревшая правка"),
                                Map.of("ru", "Не должна сохраниться"),
                                "INFO",
                                created.lockVersion())))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(
                                ((ApiException) error).getErrorCode().getDefaultStatus())
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
                .satisfies(error -> assertThat(
                                ((ApiException) error).getErrorCode().getDefaultStatus())
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
        assertThatThrownBy(() -> service.create(
                        draft(Map.of("en", "No Russian title"), Map.of("ru", "Текст"), "INFO", null), authorId))
                .isInstanceOfSatisfying(
                        ApiException.class, error -> assertThat(russian(error)).isEqualTo("RU заголовок обязателен"));

        assertThatThrownBy(() ->
                        service.create(draft(Map.of("ru", "Заголовок"), Map.of("ru", "Текст"), "HTML", null), authorId))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.getMessageKey()).isEqualTo("error.notify.banner_type_invalid"));

        assertThatThrownBy(() -> service.create(
                        draft(Map.of("ru", "Заголовок"), Map.of("ru", "x".repeat(10_001)), "INFO", null), authorId))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getMessageKey()).isEqualTo("error.notify.announcement_body_too_long");
                    assertThat(russian(error)).isEqualTo("Значение поля текст не должно превышать 10000 символов");
                });
    }

    @Test
    void localizedFieldErrorsNameTheFieldInEachLanguage() {
        assertThatThrownBy(() -> service.create(draft(Map.of(), Map.of("ru", "Текст"), "INFO", null), authorId))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(russian(error)).isEqualTo("Локализованный заголовок обязателен"));
        assertThatThrownBy(() ->
                        service.create(draft(Map.of("ru", "Заголовок"), Map.of("en", "Text"), "INFO", null), authorId))
                .isInstanceOfSatisfying(
                        ApiException.class, error -> assertThat(russian(error)).isEqualTo("RU текст обязателен"));

        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 21; i++) {
            tooMany.put("l" + i, "x");
        }
        assertThatThrownBy(() -> service.create(draft(tooMany, Map.of("ru", "Текст"), "INFO", null), authorId))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getParams()).containsEntry("max", 20);
                    assertThat(russian(error)).isEqualTo("Для поля заголовок допускается не более 20 языков");
                });
        assertThatThrownBy(() -> service.update(999_999L, draft(Map.of("ru", "З"), Map.of("ru", "Т"), "INFO", 0L)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(russian(error)).isEqualTo("Объявление не найдено: 999999"));
    }

    /** The detail a Russian client reads: the old sentence, now rendered from the catalog. */
    private static String russian(ApiException error) {
        return PackagedProblemMessages.russian()
                .render(new MockHttpServletRequest(), error.getMessageKey(), error.getParams());
    }

    @Test
    void adminControllerUsesPermissionSpecificActions() throws Exception {
        assertPermission("manage", "update");
        assertPermission("create", "create", AnnouncementDraftRequest.class);
        assertPermission("update", "update", Long.class, AnnouncementDraftRequest.class);
        assertPermission("publish", "publish", Long.class, AnnouncementVersionRequest.class);
        assertPermission("archive", "archive", Long.class, AnnouncementVersionRequest.class);
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
                        """).param("createdBy", authorId).query(Long.class).single();

        assertThat(repository.findById(id))
                .get()
                .satisfies(announcement -> assertThat(announcement.titleJson()).containsEntry("ru", null));
    }

    private static void assertPermission(String method, String action, Class<?>... parameterTypes) throws Exception {
        RequiresPermission permission = MsAnnouncementAdminController.class
                .getMethod(method, parameterTypes)
                .getAnnotation(RequiresPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.form()).isEqualTo(MsNotifyPref.FORM_ANNOUNCEMENTS);
        assertThat(permission.action()).isEqualTo(action);
    }

    private static AnnouncementDraftRequest draft(
            Map<String, String> title, Map<String, String> body, String bannerType, Long lockVersion) {
        return new AnnouncementDraftRequest(title, body, bannerType, lockVersion);
    }
}
