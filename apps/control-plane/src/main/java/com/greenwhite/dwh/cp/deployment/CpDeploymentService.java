package com.greenwhite.dwh.cp.deployment;

import com.greenwhite.dwh.cp.error.CpApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CpDeploymentService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    private static final int MAX_REASON_CODE_LENGTH = 128;
    private static final int MAX_DETAILS_LENGTH = 4000;

    private final CpDeploymentRepository repository;
    private final CpDeploymentStateMachine stateMachine = new CpDeploymentStateMachine();

    public CpDeploymentService(CpDeploymentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CpDeployment request(long instanceId,
                                UUID releaseId,
                                long generation,
                                UUID previousReleaseId) {
        if (instanceId <= 0 || releaseId == null || generation <= 0) {
            throw new CpApiException(
                    HttpStatus.BAD_REQUEST,
                    "deployment_request_invalid",
                    "Deployment request is invalid");
        }
        UUID deploymentId = repository.createRequested(
                instanceId, releaseId, generation, previousReleaseId);
        repository.appendEvent(
                deploymentId,
                1,
                requestIdempotencyKey(instanceId, generation),
                CpDeploymentStatus.REQUESTED,
                null,
                null);
        return repository.findByInstanceAndGeneration(instanceId, generation)
                .orElseThrow(() -> new IllegalStateException("Requested deployment was not persisted"));
    }

    @Transactional
    public CpDeployment transition(UUID deploymentId,
                                   long sequence,
                                   String idempotencyKey,
                                   CpDeploymentStatus next,
                                   String reasonCode,
                                   String details) {
        validateEvent(deploymentId, sequence, idempotencyKey, next, reasonCode, details);
        CpDeployment current = repository.lock(deploymentId);
        long expectedSequence = repository.nextEventSequence(deploymentId);
        boolean inserted = repository.appendEvent(
                deploymentId, sequence, idempotencyKey, next, reasonCode, details);
        if (!inserted) {
            return current;
        }
        if (sequence != expectedSequence) {
            throw new CpApiException(
                    HttpStatus.CONFLICT,
                    "deployment_event_sequence_invalid",
                    "Deployment event sequence is not contiguous");
        }

        stateMachine.requireTransition(
                current.status(),
                next,
                current.previousReleaseId() != null);
        repository.updateStatus(deploymentId, current.status(), next, reasonCode);
        return repository.lock(deploymentId);
    }

    private static void validateEvent(UUID deploymentId,
                                      long sequence,
                                      String idempotencyKey,
                                      CpDeploymentStatus next,
                                      String reasonCode,
                                      String details) {
        if (deploymentId == null
                || sequence < 2
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || next == null
                || invalidOptional(reasonCode, MAX_REASON_CODE_LENGTH)
                || invalidOptional(details, MAX_DETAILS_LENGTH)) {
            throw new CpApiException(
                    HttpStatus.BAD_REQUEST,
                    "deployment_event_invalid",
                    "Deployment event is invalid");
        }
    }

    private static boolean invalidOptional(String value, int maxLength) {
        return value != null && (value.isBlank() || value.length() > maxLength);
    }

    private static String requestIdempotencyKey(long instanceId, long generation) {
        return "deployment-request:" + instanceId + ":" + generation;
    }
}
