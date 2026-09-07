package com.greenwhite.dwh.instance.search.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.*;

/** One owner and one dedicated writer thread; future search jobs must share this coordinator. */
@Component
@Profile("!migrate")
public class SearchWorkerCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SearchWorkerCoordinator.class);
    private final SearchDeliveryWorker worker;
    private final UUID owner = UUID.randomUUID();
    private ScheduledExecutorService executor;
    private boolean started;

    public SearchWorkerCoordinator(SearchDeliveryWorker worker) { this.worker = worker; }

    /** Ready is after every ApplicationRunner, including the committed first-admin bootstrap. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "search-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                if (!started) { worker.startLifecycle(owner); started = true; }
                worker.runOnce();
            } catch (RuntimeException failure) {
                log.warn("Search background cycle failed; durable work remains pending");
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void close() {
        if (executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) log.warn("Search worker shutdown timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
