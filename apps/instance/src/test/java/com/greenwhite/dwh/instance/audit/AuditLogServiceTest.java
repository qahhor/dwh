package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditLogServiceTest {

    private final AuditLogRepository repository = Mockito.mock(AuditLogRepository.class);
    private final AuditLogService service = new AuditLogService(repository);

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
