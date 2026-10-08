package com.smartup24.cms.instance.search.api;

/** Owner-level ceilings applied to interactive search budgets. */
public interface SearchOwnerRateLimits {
    int userPerMinute();

    int tokenPerMinute();
}
