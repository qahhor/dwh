package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.*;
import com.greenwhite.dwh.instance.search.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class SearchJobService {
    private final SearchAccessPolicy access;
    private final SearchJobRepository jobs;
    private final SearchIndexStateRepository state;
    private final SearchGenerationService generations;
    private final SearchStoragePreflight storage;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private SearchMetrics metrics=SearchMetrics.unmetered();
    @org.springframework.beans.factory.annotation.Autowired
    public SearchJobService(SearchAccessPolicy access,SearchJobRepository jobs,SearchIndexStateRepository state,
            SearchGenerationService generations,SearchStoragePreflight storage,org.springframework.transaction.PlatformTransactionManager manager,
            Optional<SearchMetrics> metrics) {
        this(access,jobs,state,generations,storage,manager);this.metrics=metrics.orElseGet(SearchMetrics::unmetered);
    }
    public SearchJobService(SearchAccessPolicy access, SearchJobRepository jobs, SearchIndexStateRepository state,
            SearchGenerationService generations, SearchStoragePreflight storage, org.springframework.transaction.PlatformTransactionManager manager) {
        this.access=access; this.jobs=jobs; this.state=state;this.generations=generations;this.storage=storage;
        this.transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
        this.transaction.setTimeout(2);
    }
    public JobReceipt start(StartJobRequest request) {
        access.requireSettingsUpdate();
        if (request == null || request.requestId() == null || request.action()==null || !Set.of("CHECK","REBUILD","ROLLBACK").contains(request.action()))
            throw new ApiException(ErrorCode.BAD_REQUEST,"Invalid search job request");
        JobReceipt existing=replayedStart(request);
        if (existing!=null) return existing;
        if (request.action().equals("REBUILD")) {
            if (request.generationId()!=null) throw new ApiException(ErrorCode.BAD_REQUEST,"REBUILD_TARGET_IS_SERVER_GENERATED");
            storage.requireSpace();
        }
        return transaction.execute(tx -> {
            jobs.lockState();
            var replay=replayedStart(request);
            if (replay!=null) return replay;
            if (!request.action().equals("CHECK") && jobs.mutatingJobExists()) throw new ApiException(ErrorCode.CONFLICT,"SEARCH_JOB_IN_PROGRESS");
            UUID generation;
            if (request.action().equals("REBUILD")) generation=generations.allocate();
            else {
                generation=request.generationId()==null ? state.snapshot().generationId() : request.generationId();
                if (!jobs.generationExists(generation)) throw new ApiException(ErrorCode.NOT_FOUND,"GENERATION_NOT_FOUND");
            }
            var receipt=jobs.insert(request,generation,SecurityContext.getCurrentUserId());
            metricAfterCommit(request.action(),"QUEUED",null);
            return receipt;
        });
    }

    private JobReceipt replayedStart(StartJobRequest request) {
        var previous = jobs.byRequest(request.requestId());
        if (previous.isPresent()) {
            var existing = previous.get();
            if (!existing.recorded()) throw new ApiException(ErrorCode.CONFLICT,"REQUEST_HISTORY_UNAVAILABLE");
            if (!existing.action().equals(request.action()) || !Objects.equals(existing.requestedGenerationId(),request.generationId())
                    || existing.retryOfJobId()!=null) throw new ApiException(ErrorCode.CONFLICT,"REQUEST_CONFLICT");
            return new JobReceipt(existing.id(),existing.state());
        }
        return null;
    }

    public JobStatus current(UUID id) {
        access.requireSearchAccess();
        return required(id);
    }

    public JobPage history(int limit,String cursor) {
        access.requireSearchAccess();
        if (limit<1 || limit>100) throw new ApiException(ErrorCode.BAD_REQUEST,"Invalid job page size");
        java.time.Instant time = null;
        UUID id = null;
        if (cursor!=null) {
            try {
                if (cursor.length()>128) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
                if (parts.length!=2) throw new IllegalArgumentException();
                time=java.time.Instant.parse(parts[0]); id=UUID.fromString(parts[1]);
            } catch (RuntimeException invalid) { throw new ApiException(ErrorCode.BAD_REQUEST,"Invalid job cursor"); }
        }
        var rows=jobs.page(limit+1,time,id);
        boolean more=rows.size()>limit;
        var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        String next=more ? Base64.getUrlEncoder().withoutPadding().encodeToString((items.getLast().createdAt()+"|"+items.getLast().id())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)) : null;
        return new JobPage(items,next,more);
    }

    @Transactional(timeout=2)
    public JobReceipt cancel(UUID id) {
        access.requireSettingsUpdate(); jobs.lockState();
        var job=required(id);
        if (job.state().equals("CANCELLED")) return new JobReceipt(id,"CANCELLED");
        if (!jobs.cancel(id)) throw new ApiException(ErrorCode.CONFLICT,"JOB_CANNOT_BE_CANCELLED");
        if (job.action().equals("REBUILD")) generations.failed(job.generationId());
        jobs.auditCancellation(id,SecurityContext.getCurrentUserId());
        metricAfterCommit(job.action(),"CANCELLED",java.time.Duration.between(job.createdAt(),java.time.Instant.now()));
        return new JobReceipt(id,"CANCELLED");
    }

    @Transactional(timeout=2)
    public JobReceipt retry(UUID id,UUID requestId) {
        access.requireSettingsUpdate(); jobs.lockState();
        if (requestId==null) throw new ApiException(ErrorCode.BAD_REQUEST,"Invalid retry request");
        var old=required(id);
        var replay=jobs.byRequest(requestId);
        if (replay.isPresent()) {
            var existing=replay.get();
            if (!existing.recorded()) throw new ApiException(ErrorCode.CONFLICT,"REQUEST_HISTORY_UNAVAILABLE");
            if (!id.equals(existing.retryOfJobId())) throw new ApiException(ErrorCode.CONFLICT,"REQUEST_CONFLICT");
            return new JobReceipt(existing.id(),existing.state());
        }
        if (!Set.of("FAILED","CANCELLED").contains(old.state())) throw new ApiException(ErrorCode.CONFLICT,"JOB_CANNOT_BE_RETRIED");
        if (!jobs.generationExists(old.generationId())) throw new ApiException(ErrorCode.NOT_FOUND,"GENERATION_NOT_FOUND");
        if (!old.action().equals("CHECK") && jobs.mutatingJobExists()) throw new ApiException(ErrorCode.CONFLICT,"SEARCH_JOB_IN_PROGRESS");
        if (old.action().equals("REBUILD")) generations.retry(old.generationId());
        if (old.action().equals("ROLLBACK")) generations.retryRollback(old.generationId());
        var receipt=jobs.insert(new StartJobRequest(requestId,old.action(),old.generationId()),old.generationId(),SecurityContext.getCurrentUserId(),id);
        metricAfterCommit(old.action(),"QUEUED",null);
        return receipt;
    }

    private JobStatus required(UUID id) {
        return jobs.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"JOB_NOT_FOUND"));
    }

    @Transactional(timeout=2)
    public void failOwned(JobStatus job,UUID owner,String error) {
        jobs.lockState();
        if (jobs.fail(job.id(),owner,error) && job.action().equals("REBUILD")) generations.failed(job.generationId());
    }

    /** Startup is a system-owned request, not a fabricated request principal. */
    public void initialize(UUID owner,boolean legacy) {
        if (state.snapshot().initialized()) return;
        if (!legacy) storage.requireSpace();
        transaction.executeWithoutResult(tx -> {
            jobs.lockState();
            if (!state.owns(owner) || state.snapshot().initialized() || jobs.anyJobExists()) return;
            if (legacy) { generations.requireCapacity();state.registerLegacy(owner);return; }
            UUID generation=jobs.initialBuilding().orElseGet(generations::allocate);
            jobs.insert(new StartJobRequest(UUID.randomUUID(),"REBUILD",null),generation,null);
            metricAfterCommit("REBUILD","QUEUED",null);
        });
    }

    private void metricAfterCommit(String action,String state,java.time.Duration duration) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { metrics.job(action,state,duration); }
                });
    }
}
