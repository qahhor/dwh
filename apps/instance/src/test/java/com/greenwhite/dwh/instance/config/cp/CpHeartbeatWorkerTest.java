package com.greenwhite.dwh.instance.config.cp;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.greenwhite.dwh.instance.config.license.LicenseGateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CpHeartbeatWorkerTest {

    private final CpTelemetryRepository telemetry = Mockito.mock(CpTelemetryRepository.class);
    private final CpControlPlaneClient client = Mockito.mock(CpControlPlaneClient.class);
    private final LicenseGateService licenseService = Mockito.mock(LicenseGateService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<BuildProperties> buildProperties = Mockito.mock(ObjectProvider.class);

    @Test
    @DisplayName("Без адреса или токена heartbeat выключен")
    void shouldBeDisabledWithoutUrlOrToken() {
        assertThat(new CpClientProperties(null, null, null).enabled()).isFalse();
        assertThat(new CpClientProperties("", "token", null).enabled()).isFalse();
        assertThat(new CpClientProperties("http://cp:8081", " ", null).enabled()).isFalse();
        assertThat(new CpClientProperties("http://cp:8081", "token", null).enabled()).isTrue();
    }

    @Test
    @DisplayName("Некорректный период заменяется значением по умолчанию — 5 минут")
    void shouldFallBackToDefaultInterval() {
        assertThat(new CpClientProperties("u", "t", null).interval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new CpClientProperties("u", "t", Duration.ZERO).interval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(new CpClientProperties("u", "t", Duration.ofMinutes(-1)).interval())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(new CpClientProperties("u", "t", Duration.ofMinutes(1)).interval())
                .isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("При выключенном heartbeat база не опрашивается")
    void shouldNotTouchDatabaseWhenDisabled() {
        Mockito.when(client.enabled()).thenReturn(false);
        var worker = new CpHeartbeatWorker(client, telemetry, licenseService, buildProperties);

        worker.sendHeartbeat();

        Mockito.verifyNoInteractions(telemetry);
        Mockito.verify(client, Mockito.never()).sendHeartbeat(Mockito.any());
    }

    @Test
    @DisplayName("Воркер передаёт только фиксированные агрегаты и применяет ответ лицензии")
    void shouldSendExactTypedPayloadAndApplyLicenseReply() {
        var build = Mockito.mock(BuildProperties.class);
        Mockito.when(build.getVersion()).thenReturn("1.2.3");
        Mockito.when(buildProperties.getIfAvailable()).thenReturn(build);
        Mockito.when(client.enabled()).thenReturn(true);
        Mockito.when(telemetry.schemaVersion()).thenReturn("006");
        Mockito.when(telemetry.snapshot()).thenReturn(new CpTelemetrySnapshot(
                17, 3, 1, 1024, 4096));
        Mockito.when(client.sendHeartbeat(Mockito.any())).thenReturn(new CpHeartbeatReply(
                true, 42, "ACTIVE", "M", 7));
        var worker = new CpHeartbeatWorker(client, telemetry, licenseService, buildProperties);

        worker.sendHeartbeat();

        var payload = ArgumentCaptor.forClass(CpHeartbeatPayload.class);
        Mockito.verify(client).sendHeartbeat(payload.capture());
        assertThat(payload.getValue()).isEqualTo(new CpHeartbeatPayload(
                "1.2.3",
                "006",
                null,
                null,
                new CpHeartbeatPayload.ComponentHealth("UP", "UP", "UNKNOWN", "UNKNOWN"),
                new CpHeartbeatPayload.StorageTelemetry(1024, 4096),
                new CpHeartbeatPayload.BackupTelemetry(null, "UNKNOWN"),
                new CpHeartbeatPayload.AgentTelemetry("UNKNOWN", "UP"),
                "IDLE",
                new CpHeartbeatPayload.CapacityTelemetry(17, 3, 1)));
        Mockito.verify(licenseService).updateStatus("ACTIVE", "M");
    }

    @Test
    @DisplayName("Ошибка доставки остаётся fail-open и не раскрывает credential в журнале")
    void shouldSwallowDeliveryFailureWithoutLoggingCredential() {
        String secret = "credential-must-never-be-logged";
        Mockito.when(client.enabled()).thenReturn(true);
        Mockito.when(telemetry.schemaVersion()).thenReturn("006");
        Mockito.when(telemetry.snapshot()).thenReturn(new CpTelemetrySnapshot(
                1, 0, 0, 0, 4096));
        Mockito.when(client.sendHeartbeat(Mockito.any()))
                .thenThrow(new IllegalStateException("delivery failed for " + secret));
        var logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(CpHeartbeatWorker.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        try {
            var worker = new CpHeartbeatWorker(client, telemetry, licenseService, buildProperties);

            assertThatCode(worker::sendHeartbeat).doesNotThrowAnyException();

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allMatch(message -> !message.contains(secret));
            Mockito.verifyNoInteractions(licenseService);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
