package com.greenwhite.dwh.cp.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpDeploymentStateMachineTest {

    private final CpDeploymentStateMachine stateMachine = new CpDeploymentStateMachine();

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void permitsOnlyDocumentedNormalAndFailurePaths(Transition transition) {
        assertThatCode(() -> stateMachine.requireTransition(
                transition.current(), transition.next(), transition.previousReleaseAvailable()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSkippedMutationTerminalAndLateCancellationTransitions() {
        assertInvalid(CpDeploymentStatus.REQUESTED, CpDeploymentStatus.DEPLOYING, true);
        assertInvalid(CpDeploymentStatus.MIGRATING, CpDeploymentStatus.CANCELLED, true);
        assertInvalid(CpDeploymentStatus.SUCCEEDED, CpDeploymentStatus.ROLLING_BACK, true);

        for (CpDeploymentStatus terminal : new CpDeploymentStatus[]{
                CpDeploymentStatus.PREFLIGHT_FAILED,
                CpDeploymentStatus.BACKUP_FAILED,
                CpDeploymentStatus.SUCCEEDED,
                CpDeploymentStatus.ROLLED_BACK,
                CpDeploymentStatus.RECOVERY_REQUIRED,
                CpDeploymentStatus.CANCELLED}) {
            Arrays.stream(CpDeploymentStatus.values())
                    .forEach(next -> assertInvalid(terminal, next, true));
        }
    }

    @Test
    void rejectsRollbackWhenThereIsNoPreviousRelease() {
        assertInvalid(CpDeploymentStatus.MIGRATING, CpDeploymentStatus.ROLLING_BACK, false);
        assertInvalid(CpDeploymentStatus.DEPLOYING, CpDeploymentStatus.ROLLING_BACK, false);
        assertInvalid(CpDeploymentStatus.VERIFYING, CpDeploymentStatus.ROLLING_BACK, false);
    }

    @Test
    void rejectsNullAndSelfTransitionsWithStableErrorCode() {
        assertInvalid(null, CpDeploymentStatus.REQUESTED, true);
        assertInvalid(CpDeploymentStatus.REQUESTED, null, true);
        assertInvalid(CpDeploymentStatus.PREFLIGHT, CpDeploymentStatus.PREFLIGHT, true);
    }

    private void assertInvalid(CpDeploymentStatus current,
                               CpDeploymentStatus next,
                               boolean previousReleaseAvailable) {
        assertThatThrownBy(() -> stateMachine.requireTransition(
                current, next, previousReleaseAvailable))
                .isInstanceOfSatisfying(CpDeploymentTransitionException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("deployment_transition_invalid");
                    assertThat(error.current()).isEqualTo(current);
                    assertThat(error.next()).isEqualTo(next);
                });
    }

    private static Stream<Transition> allowedTransitions() {
        return Stream.of(
                transition(CpDeploymentStatus.REQUESTED, CpDeploymentStatus.PREFLIGHT),
                transition(CpDeploymentStatus.REQUESTED, CpDeploymentStatus.CANCELLED),
                transition(CpDeploymentStatus.PREFLIGHT, CpDeploymentStatus.BACKUP_VERIFIED),
                transition(CpDeploymentStatus.PREFLIGHT, CpDeploymentStatus.PREFLIGHT_FAILED),
                transition(CpDeploymentStatus.PREFLIGHT, CpDeploymentStatus.BACKUP_FAILED),
                transition(CpDeploymentStatus.PREFLIGHT, CpDeploymentStatus.CANCELLED),
                transition(CpDeploymentStatus.BACKUP_VERIFIED, CpDeploymentStatus.MIGRATING),
                transition(CpDeploymentStatus.BACKUP_VERIFIED, CpDeploymentStatus.CANCELLED),
                transition(CpDeploymentStatus.MIGRATING, CpDeploymentStatus.DEPLOYING),
                transition(CpDeploymentStatus.MIGRATING, CpDeploymentStatus.ROLLING_BACK),
                transition(CpDeploymentStatus.MIGRATING, CpDeploymentStatus.RECOVERY_REQUIRED),
                transition(CpDeploymentStatus.DEPLOYING, CpDeploymentStatus.VERIFYING),
                transition(CpDeploymentStatus.DEPLOYING, CpDeploymentStatus.ROLLING_BACK),
                transition(CpDeploymentStatus.DEPLOYING, CpDeploymentStatus.RECOVERY_REQUIRED),
                transition(CpDeploymentStatus.VERIFYING, CpDeploymentStatus.SUCCEEDED),
                transition(CpDeploymentStatus.VERIFYING, CpDeploymentStatus.ROLLING_BACK),
                transition(CpDeploymentStatus.VERIFYING, CpDeploymentStatus.RECOVERY_REQUIRED),
                transition(CpDeploymentStatus.ROLLING_BACK, CpDeploymentStatus.ROLLED_BACK),
                transition(CpDeploymentStatus.ROLLING_BACK, CpDeploymentStatus.RECOVERY_REQUIRED));
    }

    private static Transition transition(CpDeploymentStatus current, CpDeploymentStatus next) {
        return new Transition(current, next, true);
    }

    private record Transition(
            CpDeploymentStatus current,
            CpDeploymentStatus next,
            boolean previousReleaseAvailable) {
    }
}
