package com.smartup24.cms.instance.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler whose response carries a secret shown once (an API token, a webhook signing key). Such a
 * response is never stored for an idempotent replay: the idempotency filter runs the request without a
 * reservation, so the secret exists only in the one response that delivered it.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReturnsSecret {}
