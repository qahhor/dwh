package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.md.repository.MdCustomModuleRepository;
import com.greenwhite.dwh.instance.md.repository.MdCustomModuleRepository.CustomModuleRecord;
import com.greenwhite.dwh.instance.md.service.MdCustomModuleService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MdCustomModuleServiceTest {

    private final MdCustomModuleRepository repository = mock(MdCustomModuleRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final MdCustomModuleService service = new MdCustomModuleService(repository, auditLogService);

    @Test
    void auditsModuleCreation() {
        var created = module(11L, "sample", "DRAFT", null);
        when(repository.findByCode("sample")).thenReturn(Optional.empty());
        when(repository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

        service.createModule("Sample", "Sample", "1.0.0", null, null, null,
                null, null, null, null, 42L);

        verify(auditLogService).logChange(
                eq("md_custom_modules"), eq("11"), eq("I"), any(), eq(Map.of()), any());
    }

    @Test
    void auditsApprovalSubmission() {
        var draft = module(11L, "sample", "DRAFT", null);
        var pending = module(11L, "sample", "PENDING_APPROVAL", "TICKET-MOD-12345678");
        when(repository.findById(11L)).thenReturn(Optional.of(draft), Optional.of(pending));

        service.submitForApproval(11L);

        verify(auditLogService).logChange(
                eq("md_custom_modules"), eq("11"), eq("U"),
                eq(List.of("status", "cp_ticket_id")), any(), any());
    }

    @Test
    void auditsModuleDeletion() {
        var draft = module(11L, "sample", "DRAFT", null);
        when(repository.findById(11L)).thenReturn(Optional.of(draft));

        service.deleteModule(11L);

        verify(repository).delete(11L);
        verify(auditLogService).logChange(
                eq("md_custom_modules"), eq("11"), eq("D"), any(), any(), eq(Map.of()));
    }

    private CustomModuleRecord module(Long id, String code, String status, String ticketId) {
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new CustomModuleRecord(
                id, code, "Sample", "1.0.0", null, "custom", "extension",
                "/custom/" + code, null, "[]", "{}", status, null, ticketId,
                null, 42L, now, now
        );
    }
}
