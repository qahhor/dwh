package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.instance.audit.repository.AuditPartitionRepository;
import com.greenwhite.dwh.instance.audit.worker.AuditPartitionWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AuditPartitionWorkerTest {

    private final AuditPartitionRepository repository = Mockito.mock(AuditPartitionRepository.class);
    private final AuditPartitionWorker worker = new AuditPartitionWorker(repository, 6, 12);

    @Test
    @DisplayName("Имя партиции строится из месяца с ведущим нулём")
    void shouldBuildPartitionName() {
        assertThat(AuditPartitionRepository.partitionName(YearMonth.of(2026, 9)))
                .isEqualTo("audit_log_2026_09");
        assertThat(AuditPartitionRepository.partitionName(YearMonth.of(2027, 12)))
                .isEqualTo("audit_log_2027_12");
    }

    @Test
    @DisplayName("Создаются недостающие партиции на весь горизонт, включая переход через год")
    void shouldCreateMissingPartitionsAcrossYearBoundary() {
        when(repository.exists(any())).thenReturn(false);

        worker.ensureRunwayFrom(YearMonth.of(2026, 11));

        // текущий месяц + 6 вперёд
        Mockito.verify(repository).create(YearMonth.of(2026, 11));
        Mockito.verify(repository).create(YearMonth.of(2026, 12));
        Mockito.verify(repository).create(YearMonth.of(2027, 1));
        Mockito.verify(repository).create(YearMonth.of(2027, 5));
        Mockito.verify(repository, Mockito.times(7)).create(any());
    }

    @Test
    @DisplayName("Существующие партиции повторно не создаются")
    void shouldSkipExistingPartitions() {
        when(repository.exists(any())).thenReturn(true);

        worker.ensureRunwayFrom(YearMonth.of(2026, 10));

        Mockito.verify(repository, Mockito.never()).create(any());
    }

    @Test
    @DisplayName("Отказ на одном месяце не мешает создать остальные")
    void shouldContinueAfterFailureOnSingleMonth() {
        when(repository.exists(any())).thenReturn(false);
        Mockito.doThrow(new RuntimeException("конфликт со строками в default"))
                .when(repository).create(YearMonth.of(2026, 12));

        worker.ensureRunwayFrom(YearMonth.of(2026, 11));

        // упавший месяц не остановил цикл: остальные шесть созданы
        Mockito.verify(repository).create(YearMonth.of(2027, 1));
        Mockito.verify(repository, Mockito.times(7)).create(any());
    }

    // ------------------------------------------------------------------
    // FR-AUD-2: срок хранения оперативного журнала
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Партиции старше срока хранения отцепляются, свежие остаются")
    void shouldDetachPartitionsOlderThanRetention() {
        when(repository.attachedPartitionsBefore(YearMonth.of(2026, 1)))
                .thenReturn(java.util.List.of(YearMonth.of(2025, 8), YearMonth.of(2025, 11)));

        worker.applyRetentionFrom(YearMonth.of(2027, 1));

        Mockito.verify(repository).detachAndArchive(YearMonth.of(2025, 8));
        Mockito.verify(repository).detachAndArchive(YearMonth.of(2025, 11));
        Mockito.verify(repository, Mockito.times(2)).detachAndArchive(any());
    }

    @Test
    @DisplayName("Отказ на одной партиции не мешает отцепить остальные")
    void shouldContinueRetentionAfterFailure() {
        when(repository.attachedPartitionsBefore(any()))
                .thenReturn(java.util.List.of(YearMonth.of(2025, 8), YearMonth.of(2025, 9)));
        Mockito.doThrow(new RuntimeException("партиция занята"))
                .when(repository).detachAndArchive(YearMonth.of(2025, 8));

        worker.applyRetentionFrom(YearMonth.of(2027, 1));

        Mockito.verify(repository).detachAndArchive(YearMonth.of(2025, 9));
    }

    @Test
    @DisplayName("Нулевой срок хранения выключает отцепление: экземпляр хранит всё")
    void zeroRetentionKeepsEverything() {
        var keepAll = new AuditPartitionWorker(repository, 6, 0);

        keepAll.applyRetentionFrom(YearMonth.of(2027, 1));

        Mockito.verify(repository, Mockito.never()).attachedPartitionsBefore(any());
        Mockito.verify(repository, Mockito.never()).detachAndArchive(any());
    }
}
