package com.smartup24.cms.instance.config.system;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 10/10, item 0.7: readiness waits for the main database; the degradable dependencies are health components
 * only, and liveness stays the process alone.
 */
class ReadinessGroupIntegrationTest extends EmbeddedPostgresTest {

    @Autowired
    HealthEndpointGroups groups;

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("0.7: readiness waits for the main database only; liveness ignores the dependencies")
    void readinessWaitsForTheMainDatabase() {
        HealthEndpointGroup readiness = groups.get("readiness");
        HealthEndpointGroup liveness = groups.get("liveness");

        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("database")).isTrue();
        // A dead database must stop the traffic, not restart the process.
        assertThat(liveness.isMember("database")).isFalse();
        for (String degradable : List.of("dwh", "typesense", "clamav")) {
            assertThat(context.containsBean(degradable + "HealthIndicator")).as("%s is monitored", degradable).isTrue();
            assertThat(readiness.isMember(degradable)).as("%s outage degrades, it does not stop traffic", degradable)
                    .isFalse();
        }
    }
}
