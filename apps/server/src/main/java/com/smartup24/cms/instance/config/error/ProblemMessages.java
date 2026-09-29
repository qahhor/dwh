package com.smartup24.cms.instance.config.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/** Renders the text of an error for the response from its catalog key and parameters (plan 10/10, item 3.1). */
@FunctionalInterface
public interface ProblemMessages {

    String render(HttpServletRequest request, String key, Map<String, ?> params);
}
