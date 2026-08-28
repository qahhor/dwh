package com.greenwhite.dwh.cp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayControlPlaneScriptIntegrityTest {

    @Test
    @DisplayName("Файл миграции V001__init_cp_schema.sql должен присутствовать в classpath и содержать все таблицы CP")
    void shouldVerifyCpMigrationScriptIntegrity() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V001__init_cp_schema.sql");
        assertThat(resource.exists()).isTrue();

        try (InputStream is = resource.getInputStream()) {
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("create table cp_clients")
                    .contains("create table cp_instances")
                    .contains("create table cp_instance_heartbeats")
                    .contains("create table cp_licenses")
                    .contains("create table cp_backup_verifications")
                    .contains("create table cp_announcements")
                    .contains("create table cp_announcement_targets")
                    .contains("create table cp_announcement_contents")
                    .contains("create table cp_users")
                    .contains("create table cp_roles")
                    .contains("create table cp_user_roles");
        }
    }
}
