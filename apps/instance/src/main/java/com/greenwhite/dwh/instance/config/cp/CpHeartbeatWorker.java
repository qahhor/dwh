package com.greenwhite.dwh.instance.config.cp;

import com.greenwhite.dwh.instance.config.license.LicenseGateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Builds typed, anonymous instance telemetry and sends it to Control Plane.
 * Delivery is fail-open: tenant business functions never depend on CP reachability.
 */
@Component
@Profile("!migrate")
public class CpHeartbeatWorker {

    private static final Logger log = LoggerFactory.getLogger(CpHeartbeatWorker.class);

    private final CpControlPlaneClient client;
    private final CpTelemetryRepository telemetry;
    private final LicenseGateService licenseService;
    private final String appVersion;

    /** Avoid flooding logs with the same connectivity failure. */
    private boolean lastAttemptFailed;

    public CpHeartbeatWorker(CpControlPlaneClient client,
                             CpTelemetryRepository telemetry,
                             LicenseGateService licenseService,
                             ObjectProvider<BuildProperties> buildProperties) {
        this.client = client;
        this.telemetry = telemetry;
        this.licenseService = licenseService;
        BuildProperties build = buildProperties.getIfAvailable();
        this.appVersion = build != null && build.getVersion() != null
                && !build.getVersion().isBlank()
                ? build.getVersion()
                : "unknown";
    }

    @Scheduled(fixedDelayString = "${dwh.control-plane.interval:5m}",
               initialDelayString = "PT30S")
    public void sendHeartbeat() {
        if (!client.enabled()) {
            return;
        }

        try {
            CpTelemetrySnapshot snapshot = telemetry.snapshot();
            CpHeartbeatPayload payload = new CpHeartbeatPayload(
                    appVersion,
                    telemetry.schemaVersion(),
                    null,
                    null,
                    new CpHeartbeatPayload.ComponentHealth(
                            "UP", "UP", "UNKNOWN", "UNKNOWN"),
                    new CpHeartbeatPayload.StorageTelemetry(
                            snapshot.storageUsedBytes(),
                            snapshot.storageQuotaBytes()),
                    new CpHeartbeatPayload.BackupTelemetry(null, "UNKNOWN"),
                    new CpHeartbeatPayload.AgentTelemetry("UNKNOWN", "UP"),
                    "IDLE",
                    new CpHeartbeatPayload.CapacityTelemetry(
                            snapshot.activeUsers(),
                            snapshot.outboxPending(),
                            snapshot.outboxDeadLetter()));

            CpHeartbeatReply reply = client.sendHeartbeat(payload);
            licenseService.updateStatus(reply.licenseStatus(), reply.resourceProfile());

            if (lastAttemptFailed) {
                log.info("Связь с control plane восстановлена");
                lastAttemptFailed = false;
            }
        } catch (Exception error) {
            if (!lastAttemptFailed) {
                // Do not log the exception message: HTTP errors can contain request metadata.
                log.warn("Heartbeat в control plane не доставлен (тип ошибки: {})",
                        error.getClass().getSimpleName());
                lastAttemptFailed = true;
            }
        }
    }
}
