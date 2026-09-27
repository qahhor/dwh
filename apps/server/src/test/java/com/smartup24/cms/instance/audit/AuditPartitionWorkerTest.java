package com.smartup24.cms.instance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository;
import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository.AuditPartition;
import com.smartup24.cms.instance.audit.worker.AuditPartitionWorker;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuditPartitionWorkerTest {

    private final AuditPartitionRepository repository = Mockito.mock(AuditPartitionRepository.class);
    private final AuditPartitionWorker worker = new AuditPartitionWorker(repository, 31, 12);

    @Test
    @DisplayName("Имена партиций строятся из месяца и дня с ведущими нулями и разбираются обратно")
    void shouldBuildAndParsePartitionNames() {
        assertThat(AuditPartitionRepository.partitionName(YearMonth.of(2026, 9)))
                .isEqualTo("audit_log_2026_09");
        assertThat(AuditPartitionRepository.dayPartitionName(LocalDate.of(2026, 10, 3)))
                .isEqualTo("audit_log_2026_10_03");

        assertThat(AuditPartitionRepository.parse("audit_log_2026_10_03", true))
                .contains(new AuditPartition(
                        "audit_log_2026_10_03", LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4), true));
        assertThat(AuditPartitionRepository.parse("audit_log_archived_2025_08", false))
                .contains(new AuditPartition(
                        "audit_log_archived_2025_08", LocalDate.of(2025, 8, 1), LocalDate.of(2025, 9, 1), false));
        assertThat(AuditPartitionRepository.parse("audit_log_default", true)).isEmpty();
    }

    @Test
    @DisplayName("Запас — дневные партиции на весь горизонт, через месяц и через год")
    void shouldCreateMissingDailyPartitionsAcrossBoundaries() {
        when(repository.covers(any())).thenReturn(false);

        worker.ensureRunwayFrom(LocalDate.of(2026, 12, 20));

        // сегодня + 31 день вперёд
        Mockito.verify(repository).createDay(LocalDate.of(2026, 12, 20));
        Mockito.verify(repository).createDay(LocalDate.of(2027, 1, 1));
        Mockito.verify(repository).createDay(LocalDate.of(2027, 1, 20));
        Mockito.verify(repository, Mockito.times(32)).createDay(any());
    }

    @Test
    @DisplayName("Покрытые дни — дневной или ещё месячной партицией — повторно не создаются")
    void shouldSkipCoveredDays() {
        when(repository.covers(any())).thenReturn(true);

        worker.ensureRunwayFrom(LocalDate.of(2026, 9, 27));

        Mockito.verify(repository, Mockito.never()).createDay(any());
    }

    @Test
    @DisplayName("Отказ на одном дне не мешает создать остальные")
    void shouldContinueAfterFailureOnSingleDay() {
        when(repository.covers(any())).thenReturn(false);
        Mockito.doThrow(new RuntimeException("конфликт со строками в default"))
                .when(repository)
                .createDay(LocalDate.of(2026, 11, 2));

        worker.ensureRunwayFrom(LocalDate.of(2026, 11, 1));

        Mockito.verify(repository).createDay(LocalDate.of(2026, 11, 3));
        Mockito.verify(repository, Mockito.times(32)).createDay(any());
    }

    // ------------------------------------------------------------------
    // FR-AUD-2: срок хранения оперативного журнала
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Партиции старше срока хранения отцепляются — месячные и дневные, свежие и отцеплённые остаются")
    void shouldDetachPartitionsOlderThanRetention() {
        when(repository.partitions())
                .thenReturn(List.of(
                        month("audit_log_2025_08", 2025, 8, true),
                        month("audit_log_archived_2025_07", 2025, 7, false),
                        day("audit_log_2025_12_31", LocalDate.of(2025, 12, 31)),
                        day("audit_log_2026_01_01", LocalDate.of(2026, 1, 1)),
                        month("audit_log_2026_09", 2026, 9, true)));

        worker.applyRetentionFrom(LocalDate.of(2027, 1, 15));

        // срез — 2026-01-01: всё, что кончилось к нему, отцепляется
        Mockito.verify(repository).detachAndArchive(YearMonth.of(2025, 8));
        Mockito.verify(repository).detachDay(LocalDate.of(2025, 12, 31));
        Mockito.verify(repository, Mockito.times(1)).detachAndArchive(any());
        Mockito.verify(repository, Mockito.times(1)).detachDay(any());
    }

    @Test
    @DisplayName("Отказ на одной партиции не мешает отцепить остальные")
    void shouldContinueRetentionAfterFailure() {
        when(repository.partitions())
                .thenReturn(
                        List.of(month("audit_log_2025_08", 2025, 8, true), month("audit_log_2025_09", 2025, 9, true)));
        Mockito.doThrow(new RuntimeException("партиция занята"))
                .when(repository)
                .detachAndArchive(YearMonth.of(2025, 8));

        worker.applyRetentionFrom(LocalDate.of(2027, 1, 1));

        Mockito.verify(repository).detachAndArchive(YearMonth.of(2025, 9));
    }

    @Test
    @DisplayName("Нулевой срок хранения выключает отцепление: экземпляр хранит всё")
    void zeroRetentionKeepsEverything() {
        var keepAll = new AuditPartitionWorker(repository, 31, 0);

        keepAll.applyRetentionFrom(LocalDate.of(2027, 1, 1));

        Mockito.verify(repository, Mockito.never()).partitions();
        Mockito.verify(repository, Mockito.never()).detachAndArchive(any());
        Mockito.verify(repository, Mockito.never()).detachDay(any());
    }

    private static AuditPartition month(String name, int year, int month, boolean attached) {
        LocalDate from = LocalDate.of(year, month, 1);
        return new AuditPartition(name, from, from.plusMonths(1), attached);
    }

    private static AuditPartition day(String name, LocalDate day) {
        return new AuditPartition(name, day, day.plusDays(1), true);
    }
}
