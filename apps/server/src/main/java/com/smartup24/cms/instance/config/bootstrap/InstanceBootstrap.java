package com.smartup24.cms.instance.config.bootstrap;

import com.smartup24.cms.instance.md.service.MdInstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initializes the instance on first start (FR-INST-1):
 * instance_info and the first administrator are created from the deployment configuration,
 * not by migrations (no demo data and no well-known passwords). The md tables are written by md
 * itself ({@link MdInstanceService}, ADR-0026); the wiring only checks the configuration.
 * Idempotent: a no-op on an already initialized instance.
 */
@Component
@Profile("!migrate")
@Order(10)
public class InstanceBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstanceBootstrap.class);

    private final MdInstanceService instance;
    private final InstanceBootstrapProperties props;

    public InstanceBootstrap(MdInstanceService instance, InstanceBootstrapProperties props) {
        this.instance = instance;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initInstanceInfo();
        initFirstAdmin();
        instance.addMissingEffectivePermissions();
    }

    private void initInstanceInfo() {
        if (instance.instanceRecorded()) {
            return;
        }
        require(props.clientCode(), "smc.instance.client-code");
        require(props.clientName(), "smc.instance.client-name");
        instance.recordInstance(props.clientCode(), props.clientName(), props.resourceProfile());
        log.info("instance_initialized client_code={} profile={}", props.clientCode(), props.resourceProfile());
    }

    private void initFirstAdmin() {
        if (instance.anyUser()) {
            return;
        }
        require(props.adminLogin(), "smc.instance.admin-login");
        require(props.adminEmail(), "smc.instance.admin-email");
        require(props.adminPassword(), "smc.instance.admin-password");

        instance.createFirstAdmin(props.adminLogin(), props.adminEmail(), props.adminPassword());
        // The password is never written to the log (FR-OBS-4); the first sign-in requires a change.
        log.info("first_admin_created login={} passwordChangeRequired=true", props.adminLogin());
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The instance is not initialized: set " + property
                    + " in the deployment configuration (FR-INST-1). "
                    + "Default values are refused (AUDIT-03 C-1/C-2).");
        }
    }
}
