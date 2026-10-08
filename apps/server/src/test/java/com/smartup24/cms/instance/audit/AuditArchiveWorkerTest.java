package com.smartup24.cms.instance.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.archive.AuditArchiveService;
import com.smartup24.cms.instance.audit.worker.AuditArchiveWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Plan 10/10, item 7.3: the nightly audit archive run is timed by outcome, so a failed night raises an alert. */
class AuditArchiveWorkerTest {

    @Test
    @DisplayName("7.3: a failed archive run is logged and timed as a failure; the next run as a success")
    void runOutcomeIsMetered() {
        AuditArchiveService service = mock(AuditArchiveService.class);
        when(service.run()).thenThrow(new IllegalStateException("store away")).thenReturn(null);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        AuditArchiveWorker worker = new AuditArchiveWorker(
                service, beans.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));

        worker.archive();
        worker.archive();

        assertThat(registry.get("smc.task.run")
                        .tags("task", "audit_archive", "outcome", "failure")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("smc.task.run")
                        .tags("task", "audit_archive", "outcome", "success")
                        .timer()
                        .count())
                .isEqualTo(1);
    }
}
