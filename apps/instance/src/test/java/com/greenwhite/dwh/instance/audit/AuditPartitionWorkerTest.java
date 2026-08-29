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
    private final AuditPartitionWorker worker = new AuditPartitionWorker(repository, 6);

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
}
