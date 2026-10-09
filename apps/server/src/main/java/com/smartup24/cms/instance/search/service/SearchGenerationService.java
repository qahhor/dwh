package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.api.SearchManagementDtos;
import com.smartup24.cms.instance.search.repository.SearchGenerationRepository;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SearchGenerationService {
    private final SearchGenerationRepository generations;
    private final SearchSettingsRepository settings;
    private final SearchJobAudit audit;
    private final SearchEntities entities;
    private final int maximum;

    public SearchGenerationService(
            SearchGenerationRepository generations,
            SearchSettingsRepository settings,
            SearchJobAudit audit,
            SearchEntities entities,
            @Value("${smc.search.maximum-generations:4}") int maximum) {
        this.generations = generations;
        this.settings = settings;
        this.audit = audit;
        this.entities = entities;
        this.maximum = Math.max(4, maximum);
    }
    /** Caller owns the singleton FOR UPDATE allocation transaction. Reads an internal typed snapshot without request impersonation. */
    public UUID allocate() {
        requireCapacity();
        return generations.allocate(settings.current(), entities.all());
    }

    public void requireCapacity() {
        if (generations.registeredCount() >= maximum)
            throw new ApiException(
                    ErrorCode.CONFLICT, "error.search.generation_limit_reached", Map.of("maximum", maximum));
    }

    public void failed(UUID generation) {
        generations.failBuild(generation);
    }

    public void retry(UUID generation) {
        if (!generations.retryBuild(generation))
            throw new ApiException(ErrorCode.CONFLICT, "error.search.generation_cannot_be_retried");
    }

    public void retryRollback(UUID generation) {
        var retained = generations
                .find(generation)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.search.generation_not_found"));
        if (!retained.state().equals("RETAINED") || retained.schemaVersion() != 1)
            throw new ApiException(ErrorCode.CONFLICT, "error.search.generation_requires_rebuild");
        generations.resetRetries(generation);
    }

    public boolean finish(
            SearchReconciliationService.Proof proof,
            SearchGenerationRepository.FrozenGeneration generation,
            SearchManagementDtos.JobStatus job,
            UUID owner,
            long expectedVersion) {
        return proof.transaction(connection -> {
            boolean check = job.action().equals("CHECK");
            var barrier = generations.lockBarrier(
                    connection, owner, job.id(), check ? "VERIFYING" : "ACTIVATING", generation);
            if (barrier.isEmpty()) return false;
            if (proof.summary().successful() && !proof.revisionsUnchanged()) return false;
            if (check) {
                generations.completeCheck(
                        connection,
                        generation.id(),
                        job.id(),
                        owner,
                        generation.schemaVersion() == 1
                                && proof.summary().successful()
                                && generation.id().equals(barrier.get().activeGeneration()));
                return true;
            }
            if (!proof.summary().successful() || !generations.readyToActivate(connection, generation.id()))
                return false;
            boolean activated = generations.activate(
                    connection,
                    generation.id(),
                    job.id(),
                    owner,
                    expectedVersion,
                    barrier.get().activeGeneration());
            if (activated) audit.recordSwitch(connection, job.id());
            return activated;
        });
    }
}
