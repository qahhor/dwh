package com.smartup24.cms.instance.studio.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The health component {@code studioForms} and its meters (ADR-0038). DOWN while a stored live form fails to load,
 * with the codes and reasons in the details; it is a component of {@code /actuator/health} for monitoring only, never a
 * member of the readiness group: the broken form is left out and everything else keeps serving, so taking the instance
 * out of traffic would turn one bad form into a full outage. A switched-off module reports UP and reads nothing.
 *
 * <p>Prometheus: {@code smc_studio_forms_broken} and {@code smc_studio_forms_quarantined}, from the catalog this node
 * last compiled, without a database read at scrape time.
 */
@Component("studioFormsHealthIndicator")
@Profile("!migrate")
public class StudioFormsHealth implements HealthIndicator {
    private final StudioDefinitions definitions;

    public StudioFormsHealth(StudioDefinitions definitions, ObjectProvider<MeterRegistry> registry) {
        this.definitions = definitions;
        registry.ifAvailable(meters -> {
            Gauge.builder("smc.studio.forms.broken", definitions, StudioDefinitions::lastBroken)
                    .description("Stored live forms left out because they fail to load")
                    .register(meters);
            Gauge.builder("smc.studio.forms.quarantined", definitions, StudioDefinitions::lastQuarantined)
                    .description("Stored live forms withdrawn from serving by an operator")
                    .register(meters);
        });
    }

    @Override
    public Health health() {
        if (!definitions.active())
            return Health.up().withDetail("enabled", false).build();
        StudioDefinitions.Catalog catalog = definitions.current();
        var details = (catalog.broken().isEmpty() ? Health.up() : Health.down())
                .withDetail("stored", catalog.version().forms())
                .withDetail("served", catalog.served().size())
                .withDetail("quarantined", catalog.quarantined());
        if (!catalog.broken().isEmpty()) {
            details.withDetail(
                    "broken",
                    catalog.broken().stream()
                            .map(broken -> broken.code() + ": " + broken.reason())
                            .toList());
        }
        return details.build();
    }
}
