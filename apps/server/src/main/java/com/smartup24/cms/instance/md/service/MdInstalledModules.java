package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.common.module.InstalledModules;
import org.springframework.stereotype.Service;

/**
 * The module switch the platform reads (ADR-0032, 6.3, step 1): an entity of a switched-off module answers as an
 * unknown one. Through the registry's cached lookup.
 */
@Service
public class MdInstalledModules implements InstalledModules {

    private final ModuleRegistryService modules;

    public MdInstalledModules(ModuleRegistryService modules) {
        this.modules = modules;
    }

    @Override
    public boolean active(String module) {
        return modules.isModuleActive(module);
    }
}
