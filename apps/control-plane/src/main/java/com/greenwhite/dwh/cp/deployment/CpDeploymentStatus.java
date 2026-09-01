package com.greenwhite.dwh.cp.deployment;

public enum CpDeploymentStatus {
    REQUESTED,
    PREFLIGHT,
    PREFLIGHT_FAILED,
    BACKUP_VERIFIED,
    BACKUP_FAILED,
    MIGRATING,
    DEPLOYING,
    VERIFYING,
    SUCCEEDED,
    ROLLING_BACK,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
    CANCELLED
}
