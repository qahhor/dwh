package com.smartup24.cms.instance.config.system;

import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 10/10, item 0.7: the readiness probe waits for the dependencies, liveness does not. */
class ReadinessGroupIntegrationTest extends EmbeddedPostgresTest {

    @Autowired
    HealthEndpointGroups groups;

    @Test
    @DisplayName("0.7: readiness includes the databases, Typesense and ClamAV; liveness stays the process alone")
    void readinessWaitsForTheDependencies() {
        HealthEndpointGroup readiness = groups.get("readiness");
        HealthEndpointGroup liveness = groups.get("liveness");

        for (String member : List.of("readinessState", "database", "dwh", "typesense", "clamav")) {
            assertThat(readiness.isMember(member)).as("readiness waits for %s", member).isTrue();
            if (!member.equals("readinessState")) {
                // A dead database must stop the traffic, not restart the process.
                assertThat(liveness.isMember(member)).as("liveness ignores %s", member).isFalse();
            }
        }
    }
}
