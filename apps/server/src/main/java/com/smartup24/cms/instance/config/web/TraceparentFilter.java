package com.smartup24.cms.instance.config.web;

import com.smartup24.cms.common.filter.W3cTraceparentFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * W3C Traceparent Filter (ADR-0006).
 * Inherits the production implementation from the platform-common library.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceparentFilter extends W3cTraceparentFilter {}
