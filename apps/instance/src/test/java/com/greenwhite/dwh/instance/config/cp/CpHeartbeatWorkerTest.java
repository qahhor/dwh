package com.greenwhite.dwh.instance.config.cp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CpHeartbeatWorkerTest {

    private final CpTelemetryRepository telemetry = Mockito.mock(CpTelemetryRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<org.springframework.boot.info.BuildProperties> noBuildInfo =
            Mockito.mock(ObjectProvider.class);

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
        var worker = new CpHeartbeatWorker(new CpClientProperties(null, null, null), telemetry, noBuildInfo);

        worker.sendHeartbeat();

        Mockito.verifyNoInteractions(telemetry);
    }

    @Test
    @DisplayName("Недоступный control plane не роняет экземпляр")
    void shouldSwallowDeliveryFailure() {
        // Порт 1 гарантированно закрыт: проверяем, что отказ доставки остаётся
        // внутри воркера, а не всплывает в планировщик
        Mockito.when(telemetry.schemaVersion()).thenReturn("004");
        Mockito.when(telemetry.metrics()).thenReturn(Map.of("users", 1L));
        var worker = new CpHeartbeatWorker(
                new CpClientProperties("http://127.0.0.1:1", "token", null), telemetry, noBuildInfo);

        assertThatCode(worker::sendHeartbeat).doesNotThrowAnyException();
    }
}
