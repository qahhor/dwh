package com.smartup24.cms.instance.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.jobs.config.JobProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Plan 10/10, item 3.8: the queue settings refuse values that would break retries or leases. */
class JobPropertiesTest {

    @Test
    void refusesSettingsThatBreakRetriesOrLeases() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new JobProperties(0, second, second, second)).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new JobProperties(1, Duration.ofSeconds(-1), second, second))
                .hasMessageContaining("retry-backoff");
        assertThatThrownBy(() -> new JobProperties(1, Duration.ofSeconds(2), second, second))
                .hasMessageContaining("retry-backoff-max");
        assertThatThrownBy(() -> new JobProperties(1, second, second, Duration.ZERO))
                .hasMessageContaining("lease");
    }

    @Test
    void backoffDoublesPerFailureUpToItsCap() {
        var settings = new JobProperties(5, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5));

        assertThat(settings.backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.backoffAfter(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(settings.backoffAfter(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(settings.backoffAfter(9)).isEqualTo(Duration.ofMinutes(2));
        assertThat(JobProperties.defaults().maxAttempts()).isEqualTo(5);
    }
}
