package com.greenwhite.dwh.instance.audit.worker;

import com.greenwhite.dwh.instance.audit.repository.AuditPartitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Досоздание месячных партиций {@code audit_log} (FR-AUD-2).
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
    private final int runwayMonths;

    public AuditPartitionWorker(AuditPartitionRepository partitionRepository,
                                @Value("${dwh.audit.partition-runway-months:6}") int runwayMonths) {
        this.partitionRepository = partitionRepository;
        this.runwayMonths = runwayMonths;
    }

    /** Экземпляр мог простоять выключенным дольше запаса — проверяем сразу на старте. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureRunway();
    }

    @Scheduled(cron = "${dwh.audit.partition-cron:0 30 3 * * *}", zone = "UTC")
    public void ensureRunway() {
        ensureRunwayFrom(YearMonth.now(ZoneOffset.UTC));
    }

    /** Отдельный метод с явным месяцем: так поведение проверяется тестом без ожидания календаря. */
    public void ensureRunwayFrom(YearMonth from) {
        List<String> created = new ArrayList<>();

        for (int i = 0; i <= runwayMonths; i++) {
            YearMonth month = from.plusMonths(i);
            String name = AuditPartitionRepository.partitionName(month);
            try {
                if (!partitionRepository.exists(month)) {
                    // Каждая партиция — отдельный оператор: отказ на одном месяце
                    // не должен мешать создать остальные
                    partitionRepository.create(month);
                    created.add(name);
                }
            } catch (Exception e) {
                // Ожидаемая причина одна: строки за этот месяц уже лежат в default,
                // PostgreSQL сканирует его при создании партиции и отказывает
                log.error("Не удалось создать партицию аудита {}: {}. Перенесите строки "
                        + "за этот месяц из audit_log_default и повторите", name, e.getMessage());
            }
        }

        if (!created.isEmpty()) {
            log.info("Созданы партиции аудита: {}", String.join(", ", created));
        }

        long stranded = partitionRepository.countDefaultRows();
        if (stranded > 0) {
            log.error("В audit_log_default {} строк: партиция за какой-то месяц не была создана "
                    + "вовремя, retention эти записи не отцепит (FR-AUD-2)", stranded);
        }
    }
}
