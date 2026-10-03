package com.smartup24.cms.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.platform.api.contract.ApiPolicy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The provider SPI is part of the platform's public API (ADR-0033, 3; ADR-0011): the same rules as {@code platform-api}
 * — every public type marked, no dependency beyond the JDK and the API, nothing stable gone without a deprecation —
 * and japicmp in verify against the same baseline version.
 */
class ProviderSpiContractTest {

    private static final ApiPolicy POLICY = new ApiPolicy("com.smartup24.cms.spi");
    private static final String BASELINE = System.getProperty("platform-api.baseline.version", "1.0.0");

    @Test
    void everyPublicTypeCarriesPlatformApi() {
        POLICY.everyPublicTypeIsMarked();
    }

    @Test
    void theSpiDependsOnTheJdkAndThePlatformApiOnly() {
        POLICY.dependsOnNothingElse();
    }

    @Test
    void sinceIsAFeatureVersionNoLaterThanTheCurrentOne() {
        assertThat(POLICY.sinceProblems()).isEmpty();
    }

    @Test
    void nothingStableOfTheLastReleaseIsGoneWithoutADeprecation() {
        Path baseline = Path.of("baseline", "provider-spi-" + BASELINE + ".jar");
        Path api = Path.of("..", "platform-api", "baseline", "platform-api-" + BASELINE + ".jar");
        assertThat(POLICY.removedWithoutDeprecation(baseline, api)).isEmpty();
    }
}
