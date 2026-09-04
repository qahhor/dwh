package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.core.pagination.CursorUtils;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuditLogServiceTest {

    private final AuditLogRepository repository = Mockito.mock(AuditLogRepository.class);
    private final com.greenwhite.dwh.instance.common.metrics.PlatformMetrics metrics = Mockito.mock(com.greenwhite.dwh.instance.common.metrics.PlatformMetrics.class);
    private final AuditLogService service = new AuditLogService(repository, metrics, new AuditDataRedactor());


    @Test
    @DisplayName("Фиксация мутации сущности должна делегироваться в AuditLogRepository с параметрами")
    void shouldLogChange() {
        service.logChange("md_users", "10", "I", List.of("name", "login"), null, Map.of("name", "Alice"));

        verify(repository, times(1)).logChange(
                eq("md_users"), eq("10"), eq("I"), any(), any(), anyBoolean(),
                eq(List.of("name", "login")), isNull(), eq(Map.of("name", "Alice"))
        );
    }

    @Test
    @DisplayName("Фиксация события безопасности должна сохранять тип события и IP")
    void shouldLogSecurityEvent() {
        service.logSecurityEvent("LOGIN_SUCCESS", 5L, "192.168.1.100", "Mozilla/5.0", Map.of("device", "desktop"));

        verify(repository, times(1)).logSecurityEvent(
                eq("LOGIN_SUCCESS"), eq(5L), eq("192.168.1.100"), eq("Mozilla/5.0"), eq(Map.of("device", "desktop"))
        );
    }

    @Test
    @DisplayName("Секреты во вложенных деталях события не должны попадать в хранилище аудита")
    void shouldRedactCredentialFieldsBeforeWritingSecurityEvent() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deviceInfo", "desktop");
        details.put("credentials", Map.of("secretKey", "storage-secret", "api-token", "api-secret"));
        details.put("arrayPayload", new Object[]{Map.of("X-API-Key", "array-secret"), null});
        details.put("setPayload", Set.of(Map.of("passcode", "set-secret")));
        details.put("objectPayload", new CredentialEnvelope("object-secret", "ACTIVE"));

        service.logSecurityEvent("LOGIN_SUCCESS", 5L, "192.168.1.100", "Mozilla/5.0", details);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(repository).logSecurityEvent(
                eq("LOGIN_SUCCESS"), eq(5L), eq("192.168.1.100"), eq("Mozilla/5.0"), detailsCaptor.capture()
        );

        assertThat(detailsCaptor.getValue())
                .containsEntry("deviceInfo", "desktop")
                .containsEntry("credentials", "[REDACTED]");
        @SuppressWarnings("unchecked")
        List<Object> arrayPayload = (List<Object>) detailsCaptor.getValue().get("arrayPayload");
        assertThat(arrayPayload)
                .containsExactly(Map.of("X-API-Key", "[REDACTED]"), null);
        assertThat(detailsCaptor.getValue().get("setPayload"))
                .isEqualTo(List.of(Map.of("passcode", "[REDACTED]")));
        assertThat(detailsCaptor.getValue().get("objectPayload"))
                .isEqualTo(Map.of("apiKey", "[REDACTED]", "status", "ACTIVE"));
    }

    @Test
    @DisplayName("API аудита должен рекурсивно маскировать credential-поля, сохраняя полезные данные")
    void shouldRedactCredentialFieldsWhenReadingAuditRows() {
        var stored = new AuditLogRepository.AuditRecord(
                17L, "md_users", "5", "U", 9L, 3L, false,
                Instant.parse("2026-09-04T10:15:30Z"),
                List.of("password_hash", "profile"),
                Map.of(
                        "password_hash", "stored-hash",
                        "profile", Map.of("api-token", "nested-secret", "email", "user@example.com")
                ),
                Map.of("authorization", "Bearer live-token", "state", "A"),
                "Admin", "admin"
        );
        when(repository.listAuditLogs(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(stored));
        when(repository.countAuditLogs(any(), any(), any(), any(), any(), any())).thenReturn(1L);

        var result = service.listAuditLogs(null, null, null, null, null, null, 20, null);

        assertThat(result.items()).hasSize(1);
        var record = result.items().getFirst();
        assertThat(record.oldRow()).containsEntry("password_hash", "[REDACTED]");
        assertThat(record.newRow())
                .containsEntry("authorization", "[REDACTED]")
                .containsEntry("state", "A");
        assertThat(record.oldRow().get("profile")).isEqualTo(Map.of(
                "api-token", "[REDACTED]",
                "email", "user@example.com"
        ));
    }

    @Test
    @DisplayName("Аудит настройки должен скрывать значение секретного semantic key и capability-токены")
    void shouldRedactSensitiveSettingValueWithoutOverRedactingMetadata() {
        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("key", "oauth.client_secret");
        setting.put("value", "live-client-secret");
        setting.put("token_prefix", "dwh_public_prefix");
        setting.put("claim_token", "coordination-id");
        setting.put("reservation_token", "reservation-id");
        setting.put("authorization_url", "https://id.example.test/authorize");
        setting.put("optional", null);

        service.logChange("md_settings", "oauth.client_secret", "U", List.of("value"), null, setting);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> newRowCaptor = ArgumentCaptor.forClass(Map.class);
        verify(repository).logChange(
                eq("md_settings"), eq("oauth.client_secret"), eq("U"), any(), any(), anyBoolean(),
                eq(List.of("value")), isNull(), newRowCaptor.capture()
        );
        assertThat(newRowCaptor.getValue())
                .containsEntry("key", "oauth.client_secret")
                .containsEntry("value", "[REDACTED]")
                .containsEntry("token_prefix", "dwh_public_prefix")
                .containsEntry("claim_token", "[REDACTED]")
                .containsEntry("reservation_token", "[REDACTED]")
                .containsEntry("authorization_url", "https://id.example.test/authorize")
                .containsEntry("optional", null);
    }

    @Test
    @DisplayName("Semantic credential-пары должны распознаваться независимо от регистра и поля имени")
    void shouldRedactCredentialValuesInAlternateSemanticPairs() {
        Map<String, Object> details = Map.of(
                "upperCase", Map.of("Key", "smtp.password", "Value", "mail-secret"),
                "named", Map.of("name", "oauth.clientAssertion", "value", "signed-assertion")
        );

        service.logSecurityEvent("SETTINGS_CHANGED", 5L, null, null, details);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(repository).logSecurityEvent(
                eq("SETTINGS_CHANGED"), eq(5L), isNull(), isNull(), detailsCaptor.capture()
        );
        assertThat(detailsCaptor.getValue().get("upperCase"))
                .isEqualTo(Map.of("Key", "smtp.password", "Value", "[REDACTED]"));
        assertThat(detailsCaptor.getValue().get("named"))
                .isEqualTo(Map.of("name", "oauth.clientAssertion", "value", "[REDACTED]"));
    }

    @Test
    @DisplayName("API событий безопасности должен маскировать варианты credential-полей во вложенных массивах")
    void shouldRedactCredentialVariantsWhenReadingSecurityEvents() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("authHeader", "Bearer live");
        nested.put("proxy_authorization", "Basic live");
        nested.put("jwt", "signed.jwt.value");
        nested.put("passphrase", "open-sesame");
        nested.put("code_hash", "reset-code-hash");
        nested.put("signature", "request-signature");
        nested.put("X-API-Key", "api-key");
        nested.put("verificationCode", "123456");
        nested.put("one_time_code", "654321");
        nested.put("clientAssertion", "signed-assertion");
        nested.put("bearer", "live-bearer");
        nested.put("status", "FAILED");
        nested.put("nullable", null);
        var stored = new AuditLogRepository.SecurityEventRecord(
                21L, "LOGIN_FAILED", 5L, "192.168.1.100", "Mozilla/5.0",
                Map.of("attempts", List.of(nested)), Instant.parse("2026-09-04T10:15:30Z"),
                "User", "user"
        );
        when(repository.listSecurityEvents(any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(stored));
        when(repository.countSecurityEvents(any(), any(), any(), any(), any())).thenReturn(1L);

        var result = service.listSecurityEvents(null, null, null, null, null, 20, null);

        @SuppressWarnings("unchecked")
        var attempts = (List<Map<String, Object>>) result.items().getFirst().details().get("attempts");
        assertThat(attempts.getFirst())
                .containsEntry("authHeader", "[REDACTED]")
                .containsEntry("proxy_authorization", "[REDACTED]")
                .containsEntry("jwt", "[REDACTED]")
                .containsEntry("passphrase", "[REDACTED]")
                .containsEntry("code_hash", "[REDACTED]")
                .containsEntry("signature", "[REDACTED]")
                .containsEntry("X-API-Key", "[REDACTED]")
                .containsEntry("verificationCode", "[REDACTED]")
                .containsEntry("one_time_code", "[REDACTED]")
                .containsEntry("clientAssertion", "[REDACTED]")
                .containsEntry("bearer", "[REDACTED]")
                .containsEntry("status", "FAILED")
                .containsEntry("nullable", null);
    }

    @Test
    @DisplayName("Страница аудита должна иметь стабильный составной cursor и точное число отфильтрованных строк")
    void shouldBuildStableAuditCursorPage() {
        Instant timestamp = Instant.parse("2026-09-04T10:15:30Z");
        var newest = auditRecord(30L, timestamp);
        var second = auditRecord(20L, timestamp);
        var lookAhead = auditRecord(10L, timestamp.minusSeconds(1));
        when(repository.listAuditLogs(
                eq("md_users"), isNull(), eq("U"), isNull(), isNull(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(newest, second, lookAhead));
        when(repository.countAuditLogs(eq("md_users"), isNull(), eq("U"), isNull(), isNull(), isNull()))
                .thenReturn(73L);

        var result = service.listAuditLogs("md_users", null, "U", null, null, null, 2, null);

        assertThat(result.items()).extracting(AuditLogRepository.AuditRecord::id).containsExactly(30L, 20L);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.totalEstimated()).isEqualTo(73L);
        assertThat(CursorUtils.decode(result.nextCursor())).isEqualTo(timestamp + "|20|73");
    }

    @Test
    @DisplayName("Cursor следующей страницы и максимальный limit должны передаваться в запрос безопасно")
    void shouldDecodeCursorAndCapPageSize() {
        Instant timestamp = Instant.parse("2026-09-04T10:15:30Z");
        String cursor = CursorUtils.encode(timestamp + "|20|73");
        when(repository.listSecurityEvents(
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(timestamp), eq(20L), eq(201)))
                .thenReturn(List.of());
        var result = service.listSecurityEvents(null, null, null, null, null, 10_000, cursor);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.totalEstimated()).isEqualTo(73L);
        verify(repository, never()).countSecurityEvents(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Повреждённый cursor аудита должен отклоняться вместо возврата первой страницы")
    void shouldRejectMalformedAuditCursor() {
        assertThatThrownBy(() -> service.listAuditLogs(
                null, null, null, null, null, null, 20, "not-a-valid-cursor"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode().getCode())
                        .isEqualTo("bad_request"));
    }

    private AuditLogRepository.AuditRecord auditRecord(Long id, Instant changedAt) {
        return new AuditLogRepository.AuditRecord(
                id, "md_users", "5", "U", 1L, null, false, changedAt,
                List.of("state"), Map.of(), Map.of("state", "A"), "Admin", "admin"
        );
    }

    private record CredentialEnvelope(String apiKey, String status) {}

    @Test
    @DisplayName("Получение статистики аудита должно возвращать сводные счетчики")
    void shouldReturnAuditStats() {
        var expectedStats = new AuditLogRepository.AuditStats(150, 45, 12, 2);
        when(repository.getAuditStats()).thenReturn(expectedStats);

        var result = service.getAuditStats();

        assertThat(result.totalAuditLogs()).isEqualTo(150);
        assertThat(result.totalSecurityEvents()).isEqualTo(45);
        assertThat(result.securityEventsLast24h()).isEqualTo(12);
        assertThat(result.failedLoginsLast24h()).isEqualTo(2);
    }
}
