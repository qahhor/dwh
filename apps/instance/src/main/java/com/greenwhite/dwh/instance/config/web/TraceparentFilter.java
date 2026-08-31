package com.greenwhite.dwh.instance.config.web;

import com.greenwhite.dwh.common.filter.W3cTraceparentFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * W3C Traceparent Filter (ADR-0006, TRD-04).
 * Наследует промышленную реализацию из библиотеки platform-common.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceparentFilter extends W3cTraceparentFilter {
}
