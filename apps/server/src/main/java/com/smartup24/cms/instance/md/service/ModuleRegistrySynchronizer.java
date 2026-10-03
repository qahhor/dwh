package com.smartup24.cms.instance.md.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At start, gives every module whose manifest is on the classpath a row in the registry when it has none (ADR-0033,
 * 6.4): a module outside the monorepo is installed by its jar, and its switch, menu and runtime then work as for a
 * built-in one. A row that exists is not touched: the administrator's switch stays.
 */
@Component
@Profile("!migrate")
public class ModuleRegistrySynchronizer {

    private static final Logger log = LoggerFactory.getLogger(ModuleRegistrySynchronizer.class);

    private final ModuleRegistryService registry;

    public ModuleRegistrySynchronizer(ModuleRegistryService registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnStartup() {
        List<String> registered = registry.registerManifests();
        if (!registered.isEmpty()) {
            log.info("modules_registered codes={}", registered);
        }
    }
}
