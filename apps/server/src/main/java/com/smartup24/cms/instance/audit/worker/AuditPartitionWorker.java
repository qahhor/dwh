package com.smartup24.cms.instance.audit.worker;

import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository;
import com.smartup24.cms.instance.audit.repository.AuditPartitionRepository.AuditPartition;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Досоздание партиций {@code audit_log} вперёд и срок хранения оперативного журнала (FR-AUD-2).
 *
 * С V127 партиции дневные: журнал выгружается в архив раз в неделю или при 100 МБ, и единица выгрузки —
 * закрытая партиция. Месяцы, которые уже месячные (текущий и прошлые), такими и остаются.
 *
 * Партиция, не созданная вовремя, — тупик: аудит уходит в default, retention
 * его не отцепит, а создать нужную партицию задним числом PostgreSQL уже не
 * даст, пока подходящие строки лежат в default. Поэтому запас держим заранее:
 * при старте и затем каждую ночь.
 */
@Component
@Profile("!migrate")
public class AuditPartitionWorker {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionWorker.class);

    private final AuditPartitionRepository partitionRepository;
    private final int runwayDays;
    private final int retentionMonths;

    public AuditPartitionWorker(
            AuditPartitionRepository partitionRepository,
            @Value("${dwh.audit.partition-runway-days:31}") int runwayDays,
            @Value("${dwh.audit.retention-months:12}") int retentionMonths) {
        this.partitionRepository = partitionRepository;
        this.runwayDays = runwayDays;
        this.retentionMonths = retentionMonths;
    }

    /** Экземпляр мог простоять выключенным дольше запаса — проверяем сразу на старте. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureRunway();
    }

    @Scheduled(cron = "${dwh.audit.partition-cron:0 30 3 * * *}", zone = "UTC")
    public void ensureRunway() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ensureRunwayFrom(today);
        applyRetentionFrom(today);
    }

    /**
     * Срок хранения оперативного журнала (FR-AUD-2): партиции старше окна
     * отцепляются от {@code audit_log} и переименовываются в архивные.
     *
     * Данные здесь не удаляются. Удалить их может только выгрузка в архив, и только
     * когда эксплуатация это включила ({@code smc.audit.archive.delete-after-archive}).
     * Ноль и отрицательные значения выключают отцепление — «хранить всё».
     */
    public void applyRetentionFrom(LocalDate today) {
        if (retentionMonths <= 0) {
            return;
        }
        LocalDate cutoff = YearMonth.from(today).minusMonths(retentionMonths).atDay(1);
        List<String> archived = new ArrayList<>();

        for (AuditPartition partition : partitionRepository.partitions()) {
            if (!partition.attached() || partition.to().isAfter(cutoff)) {
                continue;
            }
            try {
                archived.add(
                        partition.daily()
                                ? partitionRepository.detachDay(partition.from())
                                : partitionRepository.detachAndArchive(YearMonth.from(partition.from())));
            } catch (Exception e) {
                log.error("Не удалось отцепить партицию аудита {}: {}", partition.name(), e.getMessage());
            }
        }

        if (!archived.isEmpty()) {
            log.warn(
                    "Срок хранения {} мес. истёк, партиции аудита отцеплены и переименованы: {}. "
                            + "Данные не удалены (FR-AUD-2)",
                    retentionMonths,
                    String.join(", ", archived));
        }
    }

    /** Отдельный метод с явной датой: так поведение проверяется тестом без ожидания календаря. */
    public void ensureRunwayFrom(LocalDate today) {
        List<String> created = new ArrayList<>();

        for (int i = 0; i <= runwayDays; i++) {
            LocalDate day = today.plusDays(i);
            try {
                if (!partitionRepository.covers(day)) {
                    // Каждая партиция — отдельный оператор: отказ на одном дне
                    // не должен мешать создать остальные
                    created.add(partitionRepository.createDay(day));
                }
            } catch (Exception e) {
                // Ожидаемая причина одна: строки за этот день уже лежат в default,
                // PostgreSQL сканирует его при создании партиции и отказывает
                log.error(
                        "Не удалось создать партицию аудита за {}: {}. Перенесите строки "
                                + "за этот день из audit_log_default и повторите",
                        day,
                        e.getMessage());
            }
        }

        if (!created.isEmpty()) {
            log.info("Созданы партиции аудита: {}", String.join(", ", created));
        }

        long stranded = partitionRepository.countDefaultRows();
        if (stranded > 0) {
            log.error(
                    "В audit_log_default {} строк: партиция за какой-то день не была создана "
                            + "вовремя, retention и архив эти записи не заберут (FR-AUD-2)",
                    stranded);
        }
    }
}
