package com.smartup24.cms.instance.kauth.api;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The authentication of a request by a Bearer API token or a session cookie, as the application's security chain
 * places it. The wiring sees this contract, not the filter that implements it (plan 10/10, item 1.3).
 */
public interface KauthRequestAuthentication extends Filter {

    /** Whether the request carries a session cookie: the CSRF check applies to such a request (FR-SEC-1). */
    boolean carriesSessionCookie(HttpServletRequest request);
}
