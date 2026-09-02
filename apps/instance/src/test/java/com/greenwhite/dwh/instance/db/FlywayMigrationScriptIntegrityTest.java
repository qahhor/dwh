package com.greenwhite.dwh.instance.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationScriptIntegrityTest {

    @Test
    @DisplayName("Файл миграции V001__init_schema.sql должен присутствовать в classpath и содержать все 26 таблиц")
    void shouldVerifyMigrationScriptIntegrity() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V001__init_schema.sql");
        assertThat(resource.exists()).isTrue();

        try (InputStream is = resource.getInputStream()) {
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            // Verify table definitions exist
            assertThat(sql)
                    .contains("create table md_instance_info")
                    .contains("create table md_custom_fields")
                    .contains("create table md_users")
                    .contains("create table md_settings")
                    .contains("create table kauth_sessions")
                    .contains("create table kauth_login_attempts")
                    .contains("create table kauth_otp_codes")
                    .contains("create table kauth_api_tokens")
                    .contains("create table kauth_password_reset_codes")
                    .contains("create table kauth_user_channels")
                    .contains("create table md_forms")
                    .contains("create table md_form_actions")
                    .contains("create table md_roles")
                    .contains("create table md_role_permissions")
                    .contains("create table md_user_roles")
                    .contains("create table md_user_permissions")
                    .contains("create table md_effective_permissions")
                    .contains("create table md_user_permission_versions")
                    .contains("create table ms_task_projects")
                    .contains("create table ms_task_project_members")
                    .contains("create table ms_task_statuses")
                    .contains("create table ms_tasks")
                    .contains("create table ms_task_members")
                    .contains("create table ms_task_comments")
                    .contains("create table ms_task_comment_files")
                    .contains("create table ms_notifications")
                    .contains("create table ms_notification_outbox")
                    .contains("create table ms_notification_prefs")
                    .contains("create table ms_announcements_cache")
                    .contains("create table ms_announcement_reads")
                    .contains("create table kwh_subscriptions")
                    .contains("create table kwh_outbox")
                    .contains("create table kwh_logs")
                    .contains("create table mf_files")
                    .contains("create table audit_log")
                    .contains("create table security_events");

            // Verify GIN indexes for JSONB dynamic attributes
            assertThat(sql)
                    .contains("create index md_users_attributes_gin_idx on md_users using gin (attributes jsonb_path_ops)")
                    .contains("create index ms_tasks_attributes_gin_idx on ms_tasks using gin (attributes jsonb_path_ops)");

            // Verify single responsible invariant index (I-T1)
            assertThat(sql)
                    .contains("create unique index ms_task_single_responsible_uq on ms_task_members (task_id) where (involve_kind = 'R')");
        }
    }

    @Test
    @DisplayName("V019 должна быть forward-only миграцией единого open-source продукта")
    void shouldContainUnifiedOpenSourceForwardMigration() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V019__unified_open_source_core.sql");
        assertThat(resource.exists()).isTrue();

        try (InputStream is = resource.getInputStream()) {
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("alter table ms_announcements_cache rename to ms_announcements")
                    .contains("rename column cp_ticket_id to legacy_approval_reference")
                    .contains("drop column license_token")
                    .contains("drop column cp_public_keys")
                    .contains("('platform.announcements', 'publish'")
                    .doesNotContain("drop table ms_announcements_cache")
                    .doesNotContain("drop table md_custom_modules");
        }
    }
}
