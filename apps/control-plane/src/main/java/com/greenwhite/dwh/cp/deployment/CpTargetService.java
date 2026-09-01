package com.greenwhite.dwh.cp.deployment;

import com.greenwhite.dwh.cp.audit.CpAuditRepository;
import com.greenwhite.dwh.cp.error.CpApiException;
import com.greenwhite.dwh.cp.instance.CpInstancePrincipal;
import com.greenwhite.dwh.cp.instance.api.CpDesiredStateResponse;
import com.greenwhite.dwh.cp.release.CpRelease;
import com.greenwhite.dwh.cp.release.CpReleaseService;
import com.greenwhite.dwh.cp.release.DeploymentMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

@Service
public class CpTargetService {

    private final CpTargetRepository repository;
    private final CpReleaseService releaseService;
    private final CpAuditRepository auditRepository;
    private final CpDeploymentService deploymentService;
    private final Clock clock;

    @Autowired
    public CpTargetService(CpTargetRepository repository,
                           CpReleaseService releaseService,
                           CpAuditRepository auditRepository,
                           CpDeploymentService deploymentService) {
        this(repository, releaseService, auditRepository, deploymentService, Clock.systemUTC());
    }

    CpTargetService(CpTargetRepository repository,
                    CpReleaseService releaseService,
                    CpAuditRepository auditRepository,
                    CpDeploymentService deploymentService,
                    Clock clock) {
        this.repository = repository;
        this.releaseService = releaseService;
        this.auditRepository = auditRepository;
        this.deploymentService = deploymentService;
        this.clock = clock;
    }

    @Transactional
    public CpTarget assign(long instanceId, AssignTargetCommand command, long actorUserId) {
        validate(command, actorUserId);
        CpTargetRepository.LockedInstance instance = repository.lockInstance(instanceId)
                .orElseThrow(() -> new CpApiException(
                        HttpStatus.NOT_FOUND,
                        "instance_not_found",
                        "Instance not found"));
        CpRelease release = requireAssignable(command.releaseId());
        DeploymentMode instanceMode;
        try {
            instanceMode = DeploymentMode.valueOf(instance.deploymentMode());
        } catch (IllegalArgumentException error) {
            throw notAssignable();
        }
        if (!release.deploymentModes().contains(instanceMode)) {
            throw notAssignable();
        }

        long currentMaximum = Math.max(
                instance.currentGeneration(),
                repository.findTargetGeneration(instanceId));
        if (currentMaximum == Long.MAX_VALUE) {
            throw new CpApiException(
                    HttpStatus.CONFLICT,
                    "target_generation_exhausted",
                    "Target generation cannot be incremented");
        }
        long generation = currentMaximum + 1;
        AssignTargetCommand normalized = normalize(command);
        repository.upsert(instanceId, generation, normalized, actorUserId, clock.instant());
        auditRepository.record(
                "OPERATOR",
                Long.toString(actorUserId),
                "instance.target.assigned",
                "instance",
                Long.toString(instanceId));
        CpTarget target = repository.findByInstanceId(instanceId)
                .orElseThrow(() -> new IllegalStateException("Assigned target was not persisted"));
        deploymentService.request(
                target.instanceId(),
                target.releaseId(),
                target.generation(),
                target.currentReleaseId());
        return target;
    }

    @Transactional(readOnly = true)
    public Optional<CpDesiredStateResponse> desiredState(CpInstancePrincipal principal) {
        if (principal == null) {
            throw new CpApiException(
                    HttpStatus.UNAUTHORIZED,
                    "instance_credential_invalid",
                    "Instance credential is invalid, expired or revoked");
        }
        return repository.findByInstanceId(principal.instanceId())
                .map(CpTargetService::toResponse);
    }

    private CpRelease requireAssignable(java.util.UUID releaseId) {
        try {
            return releaseService.requireReady(releaseId);
        } catch (CpApiException error) {
            if ("release_not_found".equals(error.errorCode())
                    || "release_not_ready".equals(error.errorCode())
                    || "release_revoked".equals(error.errorCode())) {
                throw notAssignable();
            }
            throw error;
        }
    }

    private static CpDesiredStateResponse toResponse(CpTarget target) {
        boolean reconciled = target.currentGeneration() == target.generation()
                && Objects.equals(target.currentReleaseId(), target.releaseId())
                && Objects.equals(target.currentConfigVersion(), target.configVersion());
        return new CpDesiredStateResponse(
                target.generation(),
                target.releaseId(),
                target.releaseVersion(),
                target.manifestDigest(),
                target.manifestLocation(),
                target.configVersion(),
                target.maintenanceWindow(),
                reconciled
                        ? CpDesiredStateResponse.AllowedAction.NONE
                        : CpDesiredStateResponse.AllowedAction.APPLY_RELEASE);
    }

    private static void validate(AssignTargetCommand command, long actorUserId) {
        if (command == null
                || command.releaseId() == null
                || command.configVersion() == null
                || command.configVersion().isBlank()
                || command.configVersion().length() > 64
                || command.ring() == null
                || command.maintenanceWindow() == null
                || actorUserId <= 0) {
            throw invalid();
        }
        MaintenanceWindow window = command.maintenanceWindow();
        if (window.weekOfMonth() < 1 || window.weekOfMonth() > 5
                || window.dayOfWeek() < 1 || window.dayOfWeek() > 7
                || window.start() == null
                || window.durationMinutes() < 15 || window.durationMinutes() > 240
                || window.timezone() == null || window.timezone().isBlank()
                || window.timezone().length() > 64) {
            throw invalid();
        }
        try {
            ZoneId.of(window.timezone().trim());
        } catch (DateTimeException error) {
            throw invalid();
        }
    }

    private static AssignTargetCommand normalize(AssignTargetCommand command) {
        MaintenanceWindow window = command.maintenanceWindow();
        return new AssignTargetCommand(
                command.releaseId(),
                command.configVersion().trim(),
                command.ring(),
                new MaintenanceWindow(
                        window.weekOfMonth(),
                        window.dayOfWeek(),
                        window.start(),
                        window.durationMinutes(),
                        window.timezone().trim()));
    }

    private static CpApiException invalid() {
        return new CpApiException(
                HttpStatus.BAD_REQUEST,
                "target_invalid",
                "Target assignment is invalid");
    }

    private static CpApiException notAssignable() {
        return new CpApiException(
                HttpStatus.CONFLICT,
                "release_not_assignable",
                "Release is not assignable to this instance");
    }
}
