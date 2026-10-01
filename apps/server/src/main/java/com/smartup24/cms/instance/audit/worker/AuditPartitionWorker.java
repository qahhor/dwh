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
 * Creates {@code audit_log} partitions ahead of time and applies retention to the operational log (FR-AUD-2).
 *
 * Since V127 partitions are daily: the log is exported to the archive weekly or at 100 MB, and the unit of export
 * is a closed partition. Months that are already monthly (the current and past ones) stay monthly.
 *
 * A partition not created in time is a dead end: audit rows go to default, retention
 * cannot detach it, and PostgreSQL refuses to create the right partition after the fact
 * while matching rows sit in default. So the margin is kept in advance:
 * at startup and then every night.
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
            @Value("${smc.audit.partition-runway-days:31}") int runwayDays,
            @Value("${smc.audit.retention-months:12}") int retentionMonths) {
        this.partitionRepository = partitionRepository;
        this.runwayDays = runwayDays;
        this.retentionMonths = retentionMonths;
    }

    /** The instance may have been off for longer than the margin, so the check also runs at startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureRunway();
    }

    @Scheduled(cron = "${smc.audit.partition-cron:0 30 3 * * *}", zone = "UTC")
    public void ensureRunway() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ensureRunwayFrom(today);
        applyRetentionFrom(today);
    }

    /**
     * Retention of the operational log (FR-AUD-2): partitions older than the window
     * are detached from {@code audit_log} and renamed as archived.
     *
     * No data is deleted here. Only the archive export can delete it, and only
     * when operations enabled that ({@code smc.audit.archive.delete-after-archive}).
     * Zero and negative values turn detaching off ("keep everything").
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

    /** A separate method with an explicit date, so a test can check the behavior without waiting for the calendar. */
    public void ensureRunwayFrom(LocalDate today) {
        List<String> created = new ArrayList<>();

        for (int i = 0; i <= runwayDays; i++) {
            LocalDate day = today.plusDays(i);
            try {
                if (!partitionRepository.covers(day)) {
                    // Each partition is a separate statement: a failure on one day
                    // must not prevent creating the others
                    created.add(partitionRepository.createDay(day));
                }
            } catch (Exception e) {
                // The one expected cause: rows for this day already sit in default;
                // PostgreSQL scans it when creating the partition and refuses
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
