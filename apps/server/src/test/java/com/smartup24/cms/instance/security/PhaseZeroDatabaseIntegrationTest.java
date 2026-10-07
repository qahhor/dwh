package com.smartup24.cms.instance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.ms.notify.repository.MsNotificationRepository;
import com.smartup24.cms.instance.support.TestDatabases;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Plan 10/10, phase 0 (items 0.2–0.4): the database side of the fixes, on the real migrated schema. */
class PhaseZeroDatabaseIntegrationTest {

    static JdbcClient jdbc;
    static Long userId;

    @BeforeAll
    static void setUp() {
        jdbc = JdbcClient.create(TestDatabases.migratedCopy("smc_phase_zero"));
        userId = jdbc.sql("""
                insert into md_users(name, login, email, password_hash, state)
                values('Phase Zero', 'phase0', 'phase0@example.com', 'hash', 'A') returning id
                """).query(Long.class).single();
    }

    @Test
    @DisplayName("0.4: the reminder type passes the ms_notifications check constraint")
    void reminderTypeIsAllowed() {
        var saved = new MsNotificationRepository(jdbc)
                .create(userId, "warning", "Deadline", "Soon", "/tasks", "task_deadline_1");

        assertThat(saved.type()).isEqualTo("warning");
    }

    @Test
    @DisplayName("0.3: audit partition functions are not executable by PUBLIC")
    void auditPartitionFunctionsAreNotPublic() {
        for (String function :
                new String[] {"audit_log_create_partition(int, int)", "audit_log_detach_partition(int, int)"}) {
            Boolean publicCanExecute = jdbc.sql("""
                    select exists (
                        select 1 from information_schema.routine_privileges p
                        where p.grantee = 'PUBLIC' and p.privilege_type = 'EXECUTE' and p.routine_name = :name)
                    """)
                    .param("name", function.substring(0, function.indexOf('(')))
                    .query(Boolean.class)
                    .single();
            assertThat(publicCanExecute).as(function).isFalse();
        }
    }

    @Test
    @DisplayName("V200: a monthly audit partition and its archive are not readable by PUBLIC")
    void auditPartitionsAreNotPublic() {
        jdbc.sql("select audit_log_create_partition(2021, 3)")
                .query(String.class)
                .single();
        assertThat(publicPrivileges("audit_log_2021_03")).as("attached").isZero();

        String archived = jdbc.sql("select audit_log_detach_partition(2021, 3)")
                .query(String.class)
                .single();

        assertThat(archived).isEqualTo("audit_log_archived_2021_03");
        assertThat(publicPrivileges(archived)).as("archived").isZero();
        Long open = jdbc.sql("""
                select count(*) from information_schema.table_privileges
                where grantee = 'PUBLIC' and table_name ~ '^audit_log_(archived_)?[0-9]{4}_[0-9]{2}'
                """).query(Long.class).single();
        assertThat(open).as("no audit partition is open to PUBLIC").isZero();
    }

    private static long publicPrivileges(String table) {
        return jdbc.sql("""
                        select count(*) from information_schema.table_privileges
                        where grantee = 'PUBLIC' and table_name = :table
                        """).param("table", table).query(Long.class).single();
    }

    @Test
    @DisplayName("0.3: the current and the previous month of audit_log cannot be detached")
    void recentAuditMonthsCannotBeDetached() {
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        for (YearMonth month : new YearMonth[] {now, now.minusMonths(1)}) {
            assertThatThrownBy(() -> jdbc.sql("select audit_log_detach_partition(:y, :m)")
                            .param("y", month.getYear())
                            .param("m", month.getMonthValue())
                            .query(String.class)
                            .single())
                    .hasMessageContaining("too recent");
        }
    }
}
