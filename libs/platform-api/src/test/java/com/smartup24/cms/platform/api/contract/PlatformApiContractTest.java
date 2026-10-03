package com.smartup24.cms.platform.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Plan 10/10, item 6.3, acceptance "100% of public types carry @PlatformApi" and the rules of ADR-0033, 3–5 for the
 * {@code platform-api} artifact. japicmp in verify compares the binary compatibility with the same baseline jar.
 */
class PlatformApiContractTest {

    private static final ApiPolicy POLICY = new ApiPolicy("com.smartup24.cms.platform.api");

    /** The jar of the last released API version, as japicmp reads it (ADR-0033, 4.1). */
    static Path baseline() {
        return Path.of(
                "baseline", "platform-api-" + System.getProperty("platform-api.baseline.version", "1.0.0") + ".jar");
    }

    @Test
    void everyPublicTypeCarriesPlatformApi() {
        POLICY.everyPublicTypeIsMarked();
    }

    @Test
    void theApiDependsOnTheJdkAndTheNullnessAnnotationsOnly() {
        POLICY.dependsOnNothingElse();
    }

    @Test
    void sinceIsAFeatureVersionNoLaterThanTheCurrentOne() {
        assertThat(POLICY.sinceProblems()).isEmpty();
    }

    @Test
    void nothingStableOfTheLastReleaseIsGoneWithoutADeprecation() {
        assertThat(POLICY.removedWithoutDeprecation(baseline())).isEmpty();
    }
}
