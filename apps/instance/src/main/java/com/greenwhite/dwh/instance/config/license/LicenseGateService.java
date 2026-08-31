package com.greenwhite.dwh.instance.config.license;

import com.greenwhite.dwh.instance.config.cp.CpClientProperties;
import com.greenwhite.dwh.instance.config.cp.CpTelemetryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Сервис контроля состояния лицензии и связи с Control Plane.
 * Управляет режимами допуска (ACTIVE, SUSPENDED, READ_ONLY, PENDING_ACTIVATION).
 */
@Service
public class LicenseGateService {

    private final CpClientProperties cpProps;
    private final CpTelemetryRepository telemetry;
    private final String clientCode;
    private final String clientName;
    private final String defaultProfile;

    private final AtomicReference<String> currentStatus = new AtomicReference<>("ACTIVE");
    private final AtomicReference<String> activeProfile = new AtomicReference<>("S");
    private final AtomicReference<Instant> lastSuccessfulHeartbeat = new AtomicReference<>(null);

    public LicenseGateService(
            CpClientProperties cpProps,
            CpTelemetryRepository telemetry,
            @Value("${dwh.instance.client-code:dev-local}") String clientCode,
            @Value("${dwh.instance.client-name:Local Instance}") String clientName,
            @Value("${dwh.instance.resource-profile:S}") String defaultProfile) {
        this.cpProps = cpProps;
        this.telemetry = telemetry;
        this.clientCode = clientCode;
        this.clientName = clientName;
        this.defaultProfile = defaultProfile;
        this.activeProfile.set(defaultProfile);
    }

    public void updateStatus(String status, String profile) {
        if (status != null && !status.isBlank()) {
            this.currentStatus.set(status);
        }
        if (profile != null && !profile.isBlank()) {
            this.activeProfile.set(profile);
        }
        this.lastSuccessfulHeartbeat.set(Instant.now());
    }

    public String getStatus() {
        return currentStatus.get();
    }

    public String getProfile() {
        return activeProfile.get();
    }

    public String getClientCode() {
        return clientCode;
    }

    public String getClientName() {
        return clientName;
    }

    public Instant getLastHeartbeatAt() {
        return lastSuccessfulHeartbeat.get();
    }

    public boolean isControlPlaneConfigured() {
        return cpProps.enabled();
    }

    public String getSchemaVersion() {
        return telemetry.schemaVersion();
    }

    public boolean isWriteAllowed() {
        String s = currentStatus.get();
        return "ACTIVE".equalsIgnoreCase(s);
    }

    public boolean isAccessAllowed() {
        String s = currentStatus.get();
        return !"SUSPENDED".equalsIgnoreCase(s);
    }
}
