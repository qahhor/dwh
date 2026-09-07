package com.greenwhite.dwh.instance.search;

/** Owner-level ceilings applied to interactive search budgets. */
public interface SearchOwnerRateLimits {
    int userPerMinute();

    int tokenPerMinute();
}
