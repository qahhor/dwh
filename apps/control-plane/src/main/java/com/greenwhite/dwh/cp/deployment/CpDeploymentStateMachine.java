package com.greenwhite.dwh.cp.deployment;

import java.util.Map;
import java.util.Set;

public final class CpDeploymentStateMachine {

    private static final Map<CpDeploymentStatus, Set<CpDeploymentStatus>> TRANSITIONS = Map.of(
            CpDeploymentStatus.REQUESTED,
            Set.of(CpDeploymentStatus.PREFLIGHT, CpDeploymentStatus.CANCELLED),
            CpDeploymentStatus.PREFLIGHT,
            Set.of(
                    CpDeploymentStatus.BACKUP_VERIFIED,
                    CpDeploymentStatus.PREFLIGHT_FAILED,
                    CpDeploymentStatus.BACKUP_FAILED,
                    CpDeploymentStatus.CANCELLED),
            CpDeploymentStatus.BACKUP_VERIFIED,
            Set.of(CpDeploymentStatus.MIGRATING, CpDeploymentStatus.CANCELLED),
            CpDeploymentStatus.MIGRATING,
            Set.of(
                    CpDeploymentStatus.DEPLOYING,
                    CpDeploymentStatus.ROLLING_BACK,
                    CpDeploymentStatus.RECOVERY_REQUIRED),
            CpDeploymentStatus.DEPLOYING,
            Set.of(
                    CpDeploymentStatus.VERIFYING,
                    CpDeploymentStatus.ROLLING_BACK,
                    CpDeploymentStatus.RECOVERY_REQUIRED),
            CpDeploymentStatus.VERIFYING,
            Set.of(
                    CpDeploymentStatus.SUCCEEDED,
                    CpDeploymentStatus.ROLLING_BACK,
                    CpDeploymentStatus.RECOVERY_REQUIRED),
            CpDeploymentStatus.ROLLING_BACK,
            Set.of(CpDeploymentStatus.ROLLED_BACK, CpDeploymentStatus.RECOVERY_REQUIRED));

    public void requireTransition(CpDeploymentStatus current,
                                  CpDeploymentStatus next,
                                  boolean previousReleaseAvailable) {
        if (current == null
                || next == null
                || !TRANSITIONS.getOrDefault(current, Set.of()).contains(next)
                || (next == CpDeploymentStatus.ROLLING_BACK && !previousReleaseAvailable)) {
            throw new CpDeploymentTransitionException(current, next);
        }
    }
}
