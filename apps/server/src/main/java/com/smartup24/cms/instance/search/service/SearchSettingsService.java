package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.*;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SearchSettingsService {
    private final SearchSettingsRepository repository;
    private final SearchPolicyProvider provider;
    private final SearchAccessPolicy access;
    private final AuditLogService audit;

    public SearchSettingsService(
            SearchSettingsRepository repository,
            SearchPolicyProvider provider,
            SearchAccessPolicy access,
            AuditLogService audit) {
        this.repository = repository;
        this.provider = provider;
        this.access = access;
        this.audit = audit;
    }

    public SettingsSnapshot current() {
        access.requireSettingsRead();
        return repository.current();
    }

    /** The limit of each statement of a save; inside an outer transaction too, unlike the transaction timeout. */
    private static final Duration SAVE_LIMIT = Duration.ofSeconds(2);

    @Transactional(timeout = 2)
    public SettingsSnapshot save(SaveSettingsRequest request) {
        access.requireSettingsUpdate();
        return repository.limited(SAVE_LIMIT, () -> saveNow(request));
    }

    private SettingsSnapshot saveNow(SaveSettingsRequest request) {
        var saved = repository.save(request, SecurityContext.getCurrentUserId());
        audit.logChange(
                "search_settings",
                "1",
                "U",
                List.of("version", "configuration"),
                Map.of("version", request.version()),
                Map.of(
                        "version",
                        saved.version(),
                        "schema_profile",
                        saved.policy().schemaProfile()));
        // The other nodes re-read the settings once this transaction commits (ADR-0025).
        provider.publishChange();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                provider.publishCommitted(saved);
            }
        });
        return saved;
    }
}
