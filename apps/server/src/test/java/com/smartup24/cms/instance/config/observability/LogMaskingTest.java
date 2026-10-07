package com.smartup24.cms.instance.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Plan 10/10, item 7.1: secrets never reach a log line, whatever member they hide in. */
class LogMaskingTest {

    @Test
    @DisplayName("7.1: значения пароля, токена, секрета, ключа API и OTP маскируются, ключи остаются")
    void masksSecretPairsInText() {
        assertThat(LogMasking.maskText("login failed user=42 password=Hunter2! next"))
                .isEqualTo("login failed user=42 password=*** next");
        assertThat(LogMasking.maskText("refresh_token: eyJhbGciOi.payload.sig, user_id=7"))
                .isEqualTo("refresh_token: ***, user_id=7");
        assertThat(LogMasking.maskText("{\"password\":\"Hunter2\",\"login\":\"admin\"}"))
                .isEqualTo("{\"password\":\"***\",\"login\":\"admin\"}");
        assertThat(LogMasking.maskText("client_secret=abc api_key=k-123 X-Api-Key: k-456 otp=123456"))
                .isEqualTo("client_secret=*** api_key=*** X-Api-Key: *** otp=***");
    }

    @Test
    @DisplayName("7.1: заголовки Authorization и Cookie, ссылки приглашения и сброса пароля маскируются")
    void masksHeadersAndLinks() {
        assertThat(LogMasking.maskText("Authorization: Bearer abc.def.ghi")).isEqualTo("Authorization: Bearer ***");
        assertThat(LogMasking.maskText("Cookie: SMC_SESSION=s3cr3t; XSRF-TOKEN=x"))
                .isEqualTo("Cookie: ***");
        assertThat(LogMasking.maskText("sent https://cms.example.com/reset-password#token=AbC-123_x to user 5"))
                .isEqualTo("sent https://cms.example.com/reset-password#token=*** to user 5");
        assertThat(LogMasking.maskText("callback /oauth?code=abcd1234&state=s"))
                .isEqualTo("callback /oauth?code=***&state=s");
        assertThat(LogMasking.maskText("upstream said basic dXNlcjpwYXNzd29yZA=="))
                .isEqualTo("upstream said basic ***");
    }

    @Test
    @DisplayName("7.1: обычный текст и идентификаторы не трогаются")
    void leavesOrdinaryTextAlone() {
        String text = "webhook_target_rejected outboxId=5 code=error.webhook.target entity=orders tokenizer=simple";
        assertThat(LogMasking.maskText(text)).isEqualTo(text);
        assertThat(LogMasking.isSecretName("trace_id")).isFalse();
        assertThat(LogMasking.isSecretName("errorCode")).isFalse();
        assertThat(LogMasking.isSecretName("code")).isTrue();
        assertThat(LogMasking.isSecretName("X-Auth-Token")).isTrue();
    }

    @Test
    @DisplayName(
            "7.1: JSON-строка лога: trace_id и span_id есть, секреты в сообщении, MDC и парах ключ-значение скрыты")
    void structuredLineMasksEveryMember() throws Exception {
        JsonNode line = encode(event());

        assertThat(line.path("trace_id").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(line.path("span_id").asString()).isEqualTo("00f067aa0ba902b7");
        assertThat(line.path("message").asString()).isEqualTo("sign-in user_id=42 password=*** token=***");
        assertThat(line.path("authorization").asString()).isEqualTo(LogMasking.MASK);
        assertThat(line.path("otp").asString()).isEqualTo(LogMasking.MASK);
        assertThat(line.path("note").asString()).isEqualTo("api_key=***");
        assertThat(line.toString()).doesNotContain("Hunter2", "eyJ0", "123456", "k-789", "s3cr3t");
    }

    static JsonNode encode(LoggingEvent event) throws Exception {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment());
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        try {
            String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
            assertThat(json.strip()).doesNotContain("\n");
            return new ObjectMapper().readTree(json);
        } finally {
            encoder.stop();
        }
    }

    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("spring.application.name", "smartupcms-server")
                .withProperty("logging.structured.json.rename.traceId", "trace_id")
                .withProperty("logging.structured.json.rename.spanId", "span_id")
                .withProperty("logging.structured.json.customizer", LogMaskingJsonMembersCustomizer.class.getName());
    }

    private static LoggingEvent event() {
        LoggerContext context = new LoggerContext();
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.smartup24.cms.instance.kauth.Test");
        event.setLevel(Level.INFO);
        event.setMessage("sign-in user_id={} password={} token={}");
        event.setArgumentArray(new Object[] {42, "Hunter2", "eyJ0.abc.def"});
        event.setThreadName("http-nio-1");
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(Map.of(
                "traceId", "4bf92f3577b34da6a3ce929d0e0e4736",
                "spanId", "00f067aa0ba902b7",
                "authorization", "Bearer s3cr3t",
                "note", "api_key=k-789"));
        event.addKeyValuePair(new org.slf4j.event.KeyValuePair("otp", "123456"));
        return event;
    }
}
