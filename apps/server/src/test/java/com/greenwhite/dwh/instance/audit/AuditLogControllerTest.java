package com.greenwhite.dwh.instance.audit;

import com.greenwhite.dwh.instance.audit.controller.AuditLogController;
import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditLogControllerTest {

    @Test
    void returnsAuditLogsAsCursorPage() throws Exception {
        AuditLogService service = mock(AuditLogService.class);
        var record = new AuditLogRepository.AuditRecord(
                10L, "md_users", "5", "U", 1L, null, false,
                Instant.parse("2026-09-04T10:15:30Z"), List.of("state"), Map.of(), Map.of("state", "A"),
                "Admin", "admin"
        );
        when(service.listAuditLogs(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(KeysetPage.of(List.of(record), null, false, 1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditLogController(service)).build();

        mvc.perform(get("/api/v1/audit/logs").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(10))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.totalEstimated").value(1));
    }

    @Test
    void returnsSecurityEventsAsCursorPage() throws Exception {
        AuditLogService service = mock(AuditLogService.class);
        var record = new AuditLogRepository.SecurityEventRecord(
                11L, "LOGIN_FAILED", 5L, "127.0.0.1", "Mozilla/5.0", Map.of("reason", "INVALID_PASSWORD"),
                Instant.parse("2026-09-04T10:15:30Z"), "User", "user"
        );
        when(service.listSecurityEvents(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(KeysetPage.of(List.of(record), null, false, 1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditLogController(service)).build();

        mvc.perform(get("/api/v1/audit/security-events").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(11))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.totalEstimated").value(1));
    }
}
