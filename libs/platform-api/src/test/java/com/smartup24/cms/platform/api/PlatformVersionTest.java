package com.smartup24.cms.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The semantic versions of ADR-0033, 4 and the compatibility rule of a module's minimum platform (6.3). */
class PlatformVersionTest {

    @Test
    void readsAReleaseAndAPreRelease() {
        assertThat(PlatformVersion.parse("1.2.3")).isEqualTo(new PlatformVersion(1, 2, 3, null));
        PlatformVersion snapshot = PlatformVersion.parse(" 1.0.0-SNAPSHOT ");
        assertThat(snapshot.preRelease()).isEqualTo("SNAPSHOT");
        assertThat(snapshot).hasToString("1.0.0-SNAPSHOT");
        assertThat(snapshot.feature()).isEqualTo("1.0");
    }

    @Test
    void refusesWhatIsNoVersion() {
        for (String bad : new String[] {"1.0", "01.0.0", "1.0.0-", "v1.0.0", "1.0.0.0", ""}) {
            assertThatThrownBy(() -> PlatformVersion.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PlatformVersion(-1, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlatformVersion(1, 0, 0, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ordersByItsNumbersAndComparesAPreReleaseAsItsRelease() {
        assertThat(PlatformVersion.parse("1.10.0")).isGreaterThan(PlatformVersion.parse("1.9.9"));
        assertThat(PlatformVersion.parse("2.0.0")).isGreaterThan(PlatformVersion.parse("1.99.99"));
        assertThat(PlatformVersion.parse("1.0.1")).isGreaterThan(PlatformVersion.parse("1.0.0"));
        assertThat(PlatformVersion.parse("1.0.0-SNAPSHOT")).isEqualByComparingTo(PlatformVersion.parse("1.0.0"));
    }

    @Test
    void aModuleRunsOnTheSameMajorVersionFromItsMinimumOn() {
        PlatformVersion platform = PlatformVersion.parse("1.3.0");
        assertThat(platform.satisfies(PlatformVersion.parse("1.0.0"))).isTrue();
        assertThat(platform.satisfies(PlatformVersion.parse("1.3.0"))).isTrue();
        assertThat(platform.satisfies(PlatformVersion.parse("1.4.0"))).isFalse();
        assertThat(platform.satisfies(PlatformVersion.parse("0.9.0"))).isFalse();
        assertThat(PlatformVersion.parse("2.0.0").satisfies(PlatformVersion.parse("1.0.0")))
                .isFalse();
    }

    @Test
    void theCurrentVersionIsTheOneTheBuildWrote() {
        PlatformVersion current = PlatformVersion.current();
        assertThat(current.toString()).isEqualTo(System.getProperty("platform-api.version", current.toString()));
        assertThat(current.preRelease())
                .as("the API is released with a plain version")
                .isNull();
    }
}
