/**
 * The platform's public API (ADR-0033): what a module outside the monorepo builds against. Its version, the
 * {@link com.smartup24.cms.platform.api.PlatformApi} mark of every public type and their stability are the contract;
 * everything under {@code com.smartup24.cms.instance} is the platform's implementation. Null-checked by NullAway (plan
 * 10/10, item 1.2).
 */
@NullMarked
package com.smartup24.cms.platform.api;

import org.jspecify.annotations.NullMarked;
