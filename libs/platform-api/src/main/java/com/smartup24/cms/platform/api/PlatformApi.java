package com.smartup24.cms.platform.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A type of the platform's public API (ADR-0033, 3): a module outside the monorepo may build against it, and it changes
 * only as its version and its stability allow. Every public type of {@code com.smartup24.cms.platform.api..} carries
 * it ({@code PlatformApiContractTest}); a type without it is not part of the contract.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public @interface PlatformApi {

    /** The {@code MAJOR.MINOR} version of the API that added the type ({@code 1.0}). */
    String since();

    /** What the type promises across versions (ADR-0033, 5). */
    Stability stability();
}
