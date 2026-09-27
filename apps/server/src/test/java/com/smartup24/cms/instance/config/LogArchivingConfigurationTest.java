package com.smartup24.cms.instance.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.core.rolling.helper.PeriodicityType;
import ch.qos.logback.core.rolling.helper.RollingCalendar;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Decision of 2026-09-27: the server's log file is archived every week or at 100 MB, whichever comes first. */
class LogArchivingConfigurationTest {

    @Test
    @DisplayName("Weekly periods, 100 MB parts, gzip archives, a bounded history")
    void logFileIsArchivedWeeklyOrAtOneHundredMegabytes() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties.getProperty("logging.file.name")).isEqualTo("${SMC_LOG_FILE:}");
        assertThat(properties.getProperty("logging.logback.rollingpolicy.file-name-pattern"))
                .as("a week per period (ww), a part number per 100 MB (%i), compressed")
                .isEqualTo("${logging.file.name}.%d{YYYY-'W'ww}.%i.gz");
        assertThat(properties.getProperty("logging.logback.rollingpolicy.max-file-size"))
                .isEqualTo("${SMC_LOG_MAX_FILE_SIZE:100MB}");
        assertThat(properties.getProperty("logging.logback.rollingpolicy.max-history"))
                .contains(":12}");
        assertThat(properties.getProperty("logging.logback.rollingpolicy.total-size-cap"))
                .contains(":2GB}");

        // Logback itself reads the date part as a weekly period, and the week-based year keeps names unique.
        var calendar = new RollingCalendar("YYYY-'W'ww");
        assertThat(calendar.getPeriodicityType()).isEqualTo(PeriodicityType.TOP_OF_WEEK);
        assertThat(calendar.isCollisionFree()).isTrue();
    }
}
