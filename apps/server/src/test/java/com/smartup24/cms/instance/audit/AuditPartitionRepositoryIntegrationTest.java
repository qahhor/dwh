package com.smartup24.cms.instance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository;
import com.smartup24.cms.instance.support.TestDatabases;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * FR-AUD-2: the retention of the operational log on a real PostgreSQL.
 *
 * Mocks check the window logic, but the DDL itself ({@code detach partition} and {@code rename to}) cannot be
 * checked on mocks at all, and a mistake here costs more than elsewhere: the partition leaves the log together
 * with its records.
 */
class AuditPartitionRepositoryIntegrationTest {

    static JdbcClient jdbc;
    static AuditPartitionRepository repository;

    @BeforeAll
    static void setup() {
        var ds = TestDatabases.migratedCopy("smc_partition_test");
        jdbc = JdbcClient.create(ds);
        repository = new AuditPartitionRepository(jdbc);
    }

    @Test
    @DisplayName("Отцепление уносит партицию из журнала, но сохраняет её строки в базе")
    void detachKeepsRowsButRemovesThemFromTheLog() {
        // A month well past the detach guard (V125 keeps the current and the previous month attached).
        YearMonth month = YearMonth.of(2021, 3);
        repository.create(month);
        assertThat(repository.exists(month)).isTrue();

        jdbc.sql("""
                        insert into audit_log (table_name, row_pk, event, changed_at, changed_columns)
                        values ('probe_table', '1', 'I', timestamptz '2021-03-15 10:00:00+00', array['x'])
                        """).update();
        assertThat(countInLog("probe_table")).isEqualTo(1);

        String archived = repository.detachAndArchive(month);

        assertThat(archived).isEqualTo("audit_log_archived_2021_03");
        assertThat(countInLog("probe_table"))
                .as("после отцепления записи уходят из оперативного журнала")
                .isZero();
        assertThat(countInTable(archived, "probe_table"))
                .as("но остаются в базе: удаление аудита — решение эксплуатации")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Список отцепляемых партиций не включает аварийный приёмник")
    void defaultPartitionIsNeverListedForDetach() {
        var old = repository.attachedPartitionsBefore(YearMonth.of(2027, 1));

        assertThat(old).isNotEmpty();
        assertThat(old).allSatisfy(month -> assertThat(month).isBefore(YearMonth.of(2027, 1)));
        assertThat(repository.attachedPartitionsBefore(YearMonth.of(2020, 1)))
                .as("до появления партиций отцеплять нечего")
                .isEmpty();
    }

    private static long countInLog(String tableName) {
        return jdbc.sql("select count(*) from audit_log where table_name = :t")
                .param("t", tableName)
                .query(Long.class)
                .single();
    }

    private static long countInTable(String table, String tableName) {
        return jdbc.sql("select count(*) from " + table + " where table_name = :t")
                .param("t", tableName)
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------
    // FR-AUD-1: the log is append-only
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Строку журнала нельзя изменить и нельзя удалить")
    void auditLogRowsAreImmutable() {
        jdbc.sql("""
                        insert into audit_log (table_name, row_pk, event, changed_at)
                        values ('immutability_probe', '1', 'I', now())
                        """).update();

        assertThatThrownBy(() -> jdbc.sql("update audit_log set row_pk = '2' where table_name = 'immutability_probe'")
                        .update())
                .hasMessageContaining("audit_log неизменяем");

        assertThatThrownBy(() -> jdbc.sql("delete from audit_log where table_name = 'immutability_probe'")
                        .update())
                .hasMessageContaining("audit_log неизменяем");

        assertThat(countInLog("immutability_probe"))
                .as("запись на месте: обе попытки отклонены")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Запрет не мешает отцеплению партиций: срок хранения продолжает работать")
    void immutabilityDoesNotBlockRetention() {
        YearMonth month = YearMonth.of(2021, 4);
        repository.create(month);
        jdbc.sql("""
                        insert into audit_log (table_name, row_pk, event, changed_at)
                        values ('retention_probe', '1', 'I', timestamptz '2021-04-10 10:00:00+00')
                        """).update();

        String archived = repository.detachAndArchive(month);

        assertThat(countInTable(archived, "retention_probe")).isEqualTo(1);
    }

    @Test
    @DisplayName("Создание новой партиции через функцию успешно привязывает её к audit_log")
    void createPartitionSuccessfullyCreatesAndAllowsInserts() {
        YearMonth futureMonth = YearMonth.of(2028, 5);
        assertThat(repository.exists(futureMonth)).isFalse();

        repository.create(futureMonth);
        assertThat(repository.exists(futureMonth)).isTrue();

        // The created partition accepts writes
        jdbc.sql("""
                        insert into audit_log (table_name, row_pk, event, changed_at)
                        values ('future_probe', '42', 'I', timestamptz '2028-05-15 12:00:00+00')
                        """).update();

        assertThat(countInLog("future_probe")).isEqualTo(1);
    }

    @Test
    @DisplayName("Некорректный год или месяц отклоняются с исключением")
    void invalidYearOrMonthThrowsException() {
        assertThatThrownBy(() -> repository.create(YearMonth.of(1999, 1))).hasMessageContaining("Invalid year");
    }
}
