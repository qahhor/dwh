package com.smartup24.cms.instance.config.jobs;

import com.smartup24.cms.instance.fnd.jobs.FndJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Запускатель общей очереди заданий: раз в несколько секунд ставит в очередь задания расписания, чей срок подошёл,
 * и выполняет очередь — задания всех модулей (разбор загрузок, экспорт, очистка). Живёт в обвязке приложения:
 * ядро {@code fnd} не планирует само (AC-7). В тестах выключен
 * ({@code dwh.fnd.jobs.ticker-enabled=false}) — тесты вызывают {@code runQueued()} сами.
 */
@Component
@ConditionalOnProperty(name = "dwh.fnd.jobs.ticker-enabled", matchIfMissing = true)
public class JobQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(JobQueueWorker.class);

    private final FndJobRunner runner;

    public JobQueueWorker(FndJobRunner runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${dwh.fnd.jobs.tick:PT5S}")
    public void tick() {
        try {
            runner.enqueueDue();
            runner.runQueued();
        } catch (RuntimeException failure) {
            log.error("job_tick_failed", failure);
        }
    }
}
