package com.greenwhite.dwh.instance.config.cp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Отправка heartbeat в control plane (FR-INST-3).
 *
 * Раз в период (1–5 мин) экземпляр сам сообщает о себе: версия приложения,
 * версия схемы, время старта и базовые показатели. Соединение исходящее —
 * control plane внутрь экземпляра не ходит (ADR-0004), поэтому в закрытом
 * контуре клиента достаточно разрешить один исходящий адрес.
 *
 * Отказ отправки не влияет на работу экземпляра: панель просто покажет
 * «недоступен» — это и есть назначение показателя.
 */
@Component
@Profile("!migrate")
public class CpHeartbeatWorker {

    private static final Logger log = LoggerFactory.getLogger(CpHeartbeatWorker.class);

    private static final String TOKEN_HEADER = "X-Instance-Token";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final CpClientProperties props;
    private final CpTelemetryRepository telemetry;
    private final RestClient restClient;
    private final String appVersion;
    private final long startedAtEpoch = System.currentTimeMillis();

    /** Чтобы не заваливать журнал одинаковыми ошибками, пишем только смену состояния. */
    private boolean lastAttemptFailed = false;

    public CpHeartbeatWorker(CpClientProperties props,
                             CpTelemetryRepository telemetry,
                             ObjectProvider<BuildProperties> buildProperties) {
        this.props = props;
        this.telemetry = telemetry;
        // BuildProperties появляется только если maven-плагин сгенерировал build-info;
        // в тестах и при запуске из IDE его может не быть — версия не критична
        this.appVersion = buildProperties.getIfAvailable() != null
                ? buildProperties.getObject().getVersion() : "unknown";
        this.restClient = RestClient.builder()
                .requestFactory(timeoutRequestFactory())
                .build();

        if (props.enabled()) {
            log.info("Heartbeat в control plane включён: {} каждые {} мин",
                    props.url(), props.interval().toMinutes());
        } else {
            log.info("Heartbeat в control plane выключен: не заданы "
                    + "dwh.control-plane.url и .token");
        }
    }

    @Scheduled(fixedDelayString = "${dwh.control-plane.interval:5m}",
               initialDelayString = "PT30S")
    public void sendHeartbeat() {
        if (!props.enabled()) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("appVersion", appVersion);
        body.put("schemaVersion", telemetry.schemaVersion());

        Map<String, Object> metrics = new HashMap<>(telemetry.metrics());
        metrics.put("uptimeSec", (System.currentTimeMillis() - startedAtEpoch) / 1000);
        body.put("metrics", metrics);

        try {
            restClient.post()
                    .uri(props.url() + "/api/v1/instances/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(TOKEN_HEADER, props.token())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            if (lastAttemptFailed) {
                log.info("Связь с control plane восстановлена");
                lastAttemptFailed = false;
            }
        } catch (Exception e) {
            if (!lastAttemptFailed) {
                // Токен в сообщение не попадает: логируем только адрес и причину
                log.warn("Heartbeat в control plane не доставлен ({}): {}",
                        props.url(), e.getMessage());
                lastAttemptFailed = true;
            }
        }
    }

    /** Без таймаутов зависший control plane подвесил бы поток планировщика. */
    private static ClientHttpRequestFactory timeoutRequestFactory() {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }
}
