package com.smartup24.cms.instance.md;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.md.repository.MdStorageQuotaRepository;
import com.smartup24.cms.instance.md.service.MdStorageQuotaService;
import com.smartup24.cms.instance.support.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ADR-0026: the file module's storage limits come from md through its service, with md's rule and defaults. */
class MdStorageQuotaServiceIntegrationTest {

    private static JdbcClient jdbc;
    private static MdStorageQuotaService quotas;

    @BeforeAll
    static void setup() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("md_storage_quota"));
        quotas = new MdStorageQuotaService(new MdStorageQuotaRepository(jdbc));
    }

    @Test
    @DisplayName("ADR-0026: the user's own limit wins, else the largest role limit, else 1 GB")
    void userLimitThenLargestRoleThenDefault() {
        long own = user("quota-own", 5_000L);
        long byRoles = user("quota-roles", null);
        long none = user("quota-none", null);
        grant(own, role("quota-small", 100L));
        grant(byRoles, role("quota-medium", 2_000L));
        grant(byRoles, role("quota-large", 3_000L));
        grant(none, role("quota-unset", null));

        assertThat(quotas.userQuotaBytes(own)).isEqualTo(5_000L);
        assertThat(quotas.userQuotaBytes(byRoles)).isEqualTo(3_000L);
        assertThat(quotas.userQuotaBytes(none)).isEqualTo(MdStorageQuotaService.DEFAULT_USER_QUOTA_BYTES);
        assertThat(quotas.userQuotaBytes(-1L)).isEqualTo(MdStorageQuotaService.DEFAULT_USER_QUOTA_BYTES);
        assertThat(quotas.userQuotaBytes(null)).isEqualTo(MdStorageQuotaService.DEFAULT_USER_QUOTA_BYTES);
    }

    @Test
    @DisplayName("ADR-0026: the instance limit, else 50 GB")
    void instanceLimitOrDefault() {
        long rows = jdbc.sql("select count(*) from md_instance_info")
                .query(Long.class)
                .single();
        if (rows == 0) {
            assertThat(quotas.instanceQuotaBytes()).isEqualTo(MdStorageQuotaService.DEFAULT_INSTANCE_QUOTA_BYTES);
            return;
        }
        jdbc.sql("update md_instance_info set storage_quota_bytes = null").update();
        assertThat(quotas.instanceQuotaBytes()).isEqualTo(MdStorageQuotaService.DEFAULT_INSTANCE_QUOTA_BYTES);
        jdbc.sql("update md_instance_info set storage_quota_bytes = 7000").update();
        assertThat(quotas.instanceQuotaBytes()).isEqualTo(7_000L);
    }

    private static long user(String login, Long quota) {
        return jdbc.sql("""
                        insert into md_users (name, login, email, storage_quota_bytes)
                        values (:login, :login, :login || '@example.test', :quota) returning id
                        """)
                .param("login", login)
                .param("quota", quota)
                .query(Long.class)
                .single();
    }

    private static long role(String name, Long quota) {
        return jdbc.sql("insert into md_roles (name, storage_quota_bytes) values (:name, :quota) returning id")
                .param("name", name)
                .param("quota", quota)
                .query(Long.class)
                .single();
    }

    private static void grant(long user, long role) {
        jdbc.sql("insert into md_user_roles (user_id, role_id) values (:user, :role)")
                .param("user", user)
                .param("role", role)
                .update();
    }
}
