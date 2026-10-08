package com.smartup24.cms.instance.config.web;

import com.smartup24.cms.common.filter.W3cTraceparentFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The strict gate of the incoming {@code traceparent} (plan 10/10, item 7.1): runs before the HTTP observation, so an
 * invalid header starts a new trace. The implementation lives in the platform-common library.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceparentFilter extends W3cTraceparentFilter {}
