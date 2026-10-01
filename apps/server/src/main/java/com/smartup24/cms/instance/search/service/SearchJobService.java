package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.search.dto.SearchManagementDtos.*;
import com.smartup24.cms.instance.search.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SearchJobService {
    /** The most jobs one page of the history holds. */
    static final int MAX_HISTORY_LIMIT = 100;

    private final SearchAccessPolicy access;
    private final SearchJobRepository jobs;
    private final SearchIndexStateRepository state;
    private final SearchGenerationService generations;
    private final SearchStoragePreflight storage;
    private final SearchJobAudit audit;
    private final TransactionTemplate transaction;
    private SearchMetrics metrics = SearchMetrics.unmetered();

    @Autowired
    public SearchJobService(
            SearchAccessPolicy access,
            SearchJobRepository jobs,
            SearchIndexStateRepository state,
            SearchGenerationService generations,
            SearchStoragePreflight storage,
            SearchJobAudit audit,
            PlatformTransactionManager manager,
            Optional<SearchMetrics> metrics) {
        this(access, jobs, state, generations, storage, audit, manager);
        this.metrics = metrics.orElseGet(SearchMetrics::unmetered);
    }

    public SearchJobService(
            SearchAccessPolicy access,
            SearchJobRepository jobs,
            SearchIndexStateRepository state,
            SearchGenerationService generations,
            SearchStoragePreflight storage,
            SearchJobAudit audit,
            PlatformTransactionManager manager) {
        this.access = access;
        this.jobs = jobs;
        this.state = state;
        this.generations = generations;
        this.storage = storage;
        this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(2);
    }

    public JobReceipt start(StartJobRequest request) {
        access.requireSettingsUpdate();
        if (request == null
                || request.requestId() == null
                || request.action() == null
                || !Set.of("CHECK", "REBUILD", "ROLLBACK").contains(request.action()))
            throw new ApiException(ErrorCode.BAD_REQUEST, "error.search.job_request_invalid");
        JobReceipt existing = replayedStart(request);
        if (existing != null) return existing;
        if (request.action().equals("REBUILD")) {
            if (request.generationId() != null)
                throw new ApiException(ErrorCode.BAD_REQUEST, "error.search.rebuild_target_not_allowed");
            storage.requireSpace();
        }
        return transaction.execute(tx -> {
            jobs.lockState();
            var replay = replayedStart(request);
            if (replay != null) return replay;
            if (!request.action().equals("CHECK") && jobs.mutatingJobExists())
                throw new ApiException(ErrorCode.CONFLICT, "error.search.job_in_progress");
            UUID generation;
            if (request.action().equals("REBUILD")) generation = generations.allocate();
            else {
                generation = request.generationId() == null ? state.snapshot().generationId() : request.generationId();
                if (!jobs.generationExists(generation))
                    throw new ApiException(ErrorCode.NOT_FOUND, "error.search.generation_not_found");
            }
            var receipt = insert(request, generation, SecurityContext.getCurrentUserId(), null);
            metricAfterCommit(request.action(), "QUEUED", null);
            return receipt;
        });
    }

    private JobReceipt replayedStart(StartJobRequest request) {
        var previous = jobs.byRequest(request.requestId());
        if (previous.isPresent()) {
            var existing = previous.get();
            if (!existing.recorded())
                throw new ApiException(ErrorCode.CONFLICT, "error.search.request_history_unavailable");
            if (!existing.action().equals(request.action())
                    || !Objects.equals(existing.requestedGenerationId(), request.generationId())
                    || existing.retryOfJobId() != null)
                throw new ApiException(ErrorCode.CONFLICT, "error.search.request_conflict");
            return new JobReceipt(existing.id(), existing.state());
        }
        return null;
    }

    public JobStatus current(UUID id) {
        access.requireAdministrator();
        return required(id);
    }

    public JobPage history(int limit, String cursor) {
        access.requireAdministrator();
        // Plan 10/10, item 3.5: a bad page request is 422 naming the field, as every paged list answers.
        TimePage.limit(limit, limit, MAX_HISTORY_LIMIT);
        Instant time = null;
        UUID id = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 128) throw new IllegalArgumentException();
                String[] parts = new String(
                                Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)
                        .split("\\|", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                time = Instant.parse(parts[0]);
                id = UUID.fromString(parts[1]);
            } catch (RuntimeException invalid) {
                throw TimePage.invalidCursor();
            }
        }
        var rows = jobs.page(limit + 1, time, id);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = more
                ? Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString((items.getLast().createdAt() + "|"
                                        + items.getLast().id())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : null;
        return new JobPage(items, next, more);
    }

    // The state changes below keep their 2 s limit inside an outer transaction too (SearchJobRepository#limited).
    @Transactional(timeout = 2)
    public JobReceipt cancel(UUID id) {
        return jobs.limited(() -> cancelNow(id));
    }

    private JobReceipt cancelNow(UUID id) {
        access.requireSettingsUpdate();
        jobs.lockState();
        var job = required(id);
        if (job.state().equals("CANCELLED")) return new JobReceipt(id, "CANCELLED");
        if (!jobs.cancel(id)) throw new ApiException(ErrorCode.CONFLICT, "error.search.job_cannot_be_cancelled");
        if (job.action().equals("REBUILD")) generations.failed(job.generationId());
        audit.record(id, SecurityContext.getCurrentUserId(), "CANCEL");
        metricAfterCommit(job.action(), "CANCELLED", Duration.between(job.createdAt(), Instant.now()));
        return new JobReceipt(id, "CANCELLED");
    }

    @Transactional(timeout = 2)
    public JobReceipt retry(UUID id, UUID requestId) {
        return jobs.limited(() -> retryNow(id, requestId));
    }

    private JobReceipt retryNow(UUID id, UUID requestId) {
        access.requireSettingsUpdate();
        jobs.lockState();
        if (requestId == null) throw new ApiException(ErrorCode.BAD_REQUEST, "error.search.retry_request_invalid");
        var old = required(id);
        var replay = jobs.byRequest(requestId);
        if (replay.isPresent()) {
            var existing = replay.get();
            if (!existing.recorded())
                throw new ApiException(ErrorCode.CONFLICT, "error.search.request_history_unavailable");
            if (!id.equals(existing.retryOfJobId()))
                throw new ApiException(ErrorCode.CONFLICT, "error.search.request_conflict");
            return new JobReceipt(existing.id(), existing.state());
        }
        if (!Set.of("FAILED", "CANCELLED").contains(old.state()))
            throw new ApiException(ErrorCode.CONFLICT, "error.search.job_cannot_be_retried");
        if (!jobs.generationExists(old.generationId()))
            throw new ApiException(ErrorCode.NOT_FOUND, "error.search.generation_not_found");
        if (!old.action().equals("CHECK") && jobs.mutatingJobExists())
            throw new ApiException(ErrorCode.CONFLICT, "error.search.job_in_progress");
        if (old.action().equals("REBUILD")) generations.retry(old.generationId());
        if (old.action().equals("ROLLBACK")) generations.retryRollback(old.generationId());
        var receipt = insert(
                new StartJobRequest(requestId, old.action(), old.generationId()),
                old.generationId(),
                SecurityContext.getCurrentUserId(),
                id);
        metricAfterCommit(old.action(), "QUEUED", null);
        return receipt;
    }

    private JobStatus required(UUID id) {
        return jobs.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "error.search.job_not_found"));
    }

    @Transactional(timeout = 2)
    public void failOwned(JobStatus job, UUID owner, String error) {
        jobs.limited(() -> {
            jobs.lockState();
            if (jobs.fail(job.id(), owner, error) && job.action().equals("REBUILD"))
                generations.failed(job.generationId());
            return Boolean.TRUE;
        });
    }

    /** Startup is a system-owned request, not a fabricated request principal. */
    public void initialize(UUID owner, boolean legacy) {
        if (state.snapshot().initialized()) return;
        if (!legacy) storage.requireSpace();
        transaction.executeWithoutResult(tx -> {
            jobs.lockState();
            if (!state.owns(owner) || state.snapshot().initialized() || jobs.anyJobExists()) return;
            if (legacy) {
                generations.requireCapacity();
                state.registerLegacy(owner);
                return;
            }
            UUID generation = jobs.initialBuilding().orElseGet(generations::allocate);
            insert(new StartJobRequest(UUID.randomUUID(), "REBUILD", null), generation, null, null);
            metricAfterCommit("REBUILD", "QUEUED", null);
        });
    }

    /** Queues the job and records its start or retry in the audit log, in the caller's transaction. */
    private JobReceipt insert(StartJobRequest request, UUID generation, Long actor, UUID retryOf) {
        var receipt = jobs.insert(request, generation, actor, retryOf);
        audit.record(receipt.id(), actor, retryOf == null ? "START" : "RETRY");
        return receipt;
    }

    private void metricAfterCommit(String action, String state, Duration duration) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.job(action, state, duration);
            }
        });
    }
}
