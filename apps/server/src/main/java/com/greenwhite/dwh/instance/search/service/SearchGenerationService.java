package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.search.repository.SearchGenerationRepository;
import com.greenwhite.dwh.instance.search.repository.SearchSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class SearchGenerationService {
    private final SearchGenerationRepository generations;
    private final SearchSettingsRepository settings;
    private final int maximum;
    public SearchGenerationService(SearchGenerationRepository generations,SearchSettingsRepository settings,
            @Value("${dwh.search.maximum-generations:4}") int maximum) {
        this.generations=generations;this.settings=settings;this.maximum=Math.max(4,maximum);
    }
    /** Caller owns the singleton FOR UPDATE allocation transaction. Reads an internal typed snapshot without request impersonation. */
    public UUID allocate() {
        requireCapacity();
        return generations.allocate(settings.current());
    }
    public void requireCapacity() {
        if (generations.registeredCount()>=maximum) throw new ApiException(ErrorCode.CONFLICT,"GENERATION_LIMIT_REACHED");
    }
    public void failed(UUID generation) { generations.failBuild(generation); }
    public void retry(UUID generation) {
        if (!generations.retryBuild(generation)) throw new ApiException(ErrorCode.CONFLICT,"GENERATION_CANNOT_BE_RETRIED");
    }
    public void retryRollback(UUID generation) {
        var retained=generations.find(generation).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"GENERATION_NOT_FOUND"));
        if (!retained.state().equals("RETAINED") || retained.schemaVersion()!=1)
            throw new ApiException(ErrorCode.CONFLICT,"GENERATION_REQUIRES_REBUILD");
        generations.resetRetries(generation);
    }
    public boolean finish(SearchReconciliationService.Proof proof,SearchGenerationRepository.FrozenGeneration generation,
                          com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.JobStatus job,UUID owner,long expectedVersion) {
        return proof.transaction(connection -> {
            boolean check=job.action().equals("CHECK");
            var barrier=generations.lockBarrier(connection,owner,job.id(),check ? "VERIFYING" : "ACTIVATING",generation);
            if (barrier.isEmpty()) return false;
            if (proof.summary().successful() && !proof.revisionsUnchanged()) return false;
            if (check) {
                generations.completeCheck(connection,generation.id(),job.id(),owner,
                        generation.schemaVersion()==1 && proof.summary().successful()
                                && generation.id().equals(barrier.get().activeGeneration()));
                return true;
            }
            if (!proof.summary().successful() || !generations.readyToActivate(connection,generation.id())) return false;
            return generations.activate(connection,generation.id(),job.id(),owner,expectedVersion,barrier.get().activeGeneration());
        });
    }
}
