package com.smartup24.cms.instance.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The handler answers the record's new revision in {@code ETag} without a body ({@code 204}), the value the next
 * change sends in {@code If-Match} (plan 10/10, item 3.6, ADR-0024). An answer that is a {@link Revisioned} record
 * carries it without this mark. The API description declares the header from it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AnswersRevision {}
