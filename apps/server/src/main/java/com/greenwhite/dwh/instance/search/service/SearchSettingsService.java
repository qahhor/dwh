package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.*;
import com.greenwhite.dwh.instance.search.repository.SearchSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Map;

@Service
public class SearchSettingsService {
    private final SearchSettingsRepository repository;
    private final SearchPolicyProvider provider;
    private final SearchAccessPolicy access;
    private final AuditLogService audit;
    public SearchSettingsService(SearchSettingsRepository repository, SearchPolicyProvider provider,
                                 SearchAccessPolicy access, AuditLogService audit) {
        this.repository=repository; this.provider=provider; this.access=access; this.audit=audit;
    }
    public SettingsSnapshot current() {
        access.requireSettingsRead();
        return repository.current();
    }
    @Transactional(timeout=2)
    public SettingsSnapshot save(SaveSettingsRequest request) {
        access.requireSettingsUpdate();
        var saved = repository.save(request, SecurityContext.getCurrentUserId());
        audit.logChange("search_settings", "1", "U", List.of("version", "configuration"),
                Map.of("version",request.version()), Map.of("version",saved.version(),"schema_profile",saved.policy().schemaProfile()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { provider.publishCommitted(saved); }
        });
        return saved;
    }
}
