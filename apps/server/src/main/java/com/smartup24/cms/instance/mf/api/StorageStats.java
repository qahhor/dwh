package com.smartup24.cms.instance.mf.api;

/** Company and personal storage use against the quotas. */
public record StorageStats(
        long companyQuotaBytes,
        long companyUsedBytes,
        long companyAvailableBytes,
        long userQuotaBytes,
        long userUsedBytes,
        long userAvailableBytes,
        int totalFilesCount,
        int userFilesCount) {}
