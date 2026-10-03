package com.smartup24.cms.platform.api;

/**
 * What a type of the platform's API promises across versions (ADR-0033, 5). Both levels are compared by japicmp: an
 * incompatible change of either needs a new major version of the API.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public enum Stability {
    /**
     * Changed incompatibly only in a major version, and only after it was {@code @Deprecated} in at least one released
     * minor version before.
     */
    STABLE,
    /** May be removed or changed in the next major version without a deprecation first. */
    EXPERIMENTAL
}
