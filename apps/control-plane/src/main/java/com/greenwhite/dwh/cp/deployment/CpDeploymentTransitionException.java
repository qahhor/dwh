package com.greenwhite.dwh.cp.deployment;

import com.greenwhite.dwh.cp.error.CpApiException;
import org.springframework.http.HttpStatus;

public final class CpDeploymentTransitionException extends CpApiException {

    private final CpDeploymentStatus current;
    private final CpDeploymentStatus next;

    public CpDeploymentTransitionException(CpDeploymentStatus current,
                                           CpDeploymentStatus next) {
        super(
                HttpStatus.CONFLICT,
                "deployment_transition_invalid",
                "Deployment transition is not allowed: " + current + " -> " + next);
        this.current = current;
        this.next = next;
    }

    public CpDeploymentStatus current() {
        return current;
    }

    public CpDeploymentStatus next() {
        return next;
    }
}
