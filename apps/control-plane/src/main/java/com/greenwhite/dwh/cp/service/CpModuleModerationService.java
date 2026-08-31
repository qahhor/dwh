package com.greenwhite.dwh.cp.service;

import com.greenwhite.dwh.cp.repository.CpModuleModerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CpModuleModerationService {

    private static final Logger log = LoggerFactory.getLogger(CpModuleModerationService.class);

    private final CpModuleModerationRepository repository;

    public CpModuleModerationService(CpModuleModerationRepository repository) {
        this.repository = repository;
    }

    public List<CpModuleModerationRepository.InstanceModuleRecord> listAll() {
        return repository.findAll();
    }

    public CpModuleModerationRepository.InstanceModuleRecord getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Заявка на модуль не найдена: " + id));
    }

    @Transactional
    public CpModuleModerationRepository.InstanceModuleRecord submitModuleFromInstance(
            Long instanceId, String clientCode, String moduleCode, String name,
            String version, String description, String category, String icon,
            String routePath, String entrypointUrl, String permissionsJson) {

        log.info("Received custom module submission from client {} (instanceId={}): code={}", clientCode, instanceId, moduleCode);
        return repository.upsertSubmission(
                instanceId, clientCode, moduleCode, name, version,
                description, category, icon, routePath, entrypointUrl, permissionsJson
        );
    }

    @Transactional
    public CpModuleModerationRepository.InstanceModuleRecord approveModule(Long id, String notes, String reviewer) {
        var mod = getById(id);
        repository.updateModerationStatus(id, "APPROVED", notes, reviewer);
        log.info("Custom module {} for client {} APPROVED by {}", mod.moduleCode(), mod.clientCode(), reviewer);
        return getById(id);
    }

    @Transactional
    public CpModuleModerationRepository.InstanceModuleRecord rejectModule(Long id, String notes, String reviewer) {
        var mod = getById(id);
        repository.updateModerationStatus(id, "REJECTED", notes, reviewer);
        log.warn("Custom module {} for client {} REJECTED by {}: reason={}", mod.moduleCode(), mod.clientCode(), reviewer, notes);
        return getById(id);
    }
}
