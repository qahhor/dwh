package com.smartup24.cms.instance.md.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the technical {@code system} account at application startup. Order 20 runs strictly after
 * the platform's {@code InstanceBootstrap} (order 10): it creates the first administrator only when
 * {@code md_users} is empty, so the foundation account must not appear before it.
 */
@Component
@Profile("!migrate")
@Order(20)
public class MdSystemAccountBootstrap implements ApplicationRunner {

    private final MdAuditActors actors;

    public MdSystemAccountBootstrap(MdAuditActors actors) {
        this.actors = actors;
    }

    @Override
    public void run(ApplicationArguments args) {
        actors.ensureSystem();
    }
}
