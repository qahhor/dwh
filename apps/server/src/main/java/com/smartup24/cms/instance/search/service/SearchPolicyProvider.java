package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.cluster.ClusterNotices;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.api.SearchManagementDtos.SettingsSnapshot;
import com.smartup24.cms.instance.search.api.SearchOwnerRateLimits;
import com.smartup24.cms.instance.search.repository.SearchSettingsRepository;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Shared source of the active query policy and its owner-clamped rate budgets. Each node keeps the committed settings
 * in memory; a save on any node publishes the notice {@link #NOTICE} through {@link ClusterNotices} (ADR-0025), and
 * every other node re-reads the settings at once. The periodic re-read only repairs a node whose read failed or
 * that missed a notice.
 */
@Service
public class SearchPolicyProvider {

    /** The cluster notice of a settings change (ADR-0025). */
    public static final String NOTICE = "searchSettings";

    private static final long FALLBACK_NANOS = Duration.ofMinutes(1).toNanos();

    private static final Logger log = LoggerFactory.getLogger(SearchPolicyProvider.class);
    private final SearchOwnerRateLimits ownerRateLimits;
    private final SearchSettingsRepository repository;
    private final @Nullable ClusterNotices cluster;
    private volatile SettingsSnapshot snapshot;
    private volatile boolean degraded = true;
    private volatile long lastRead = System.nanoTime();

    /** A single node: nothing tells it of a change made elsewhere. */
    public SearchPolicyProvider(SearchOwnerRateLimits ownerRateLimits, SearchSettingsRepository repository) {
        this(ownerRateLimits, repository, (ClusterNotices) null);
    }

    @Autowired
    public SearchPolicyProvider(
            SearchOwnerRateLimits ownerRateLimits,
            SearchSettingsRepository repository,
            ObjectProvider<ClusterNotices> cluster) {
        this(ownerRateLimits, repository, cluster.getIfAvailable());
    }

    public SearchPolicyProvider(
            SearchOwnerRateLimits ownerRateLimits,
            SearchSettingsRepository repository,
            @Nullable ClusterNotices cluster) {
        this.ownerRateLimits = ownerRateLimits;
        this.repository = repository;
        this.cluster = cluster;
        if (cluster != null) {
            cluster.onNotice(NOTICE, this::refresh);
        }
    }

    /** Tells the other nodes that the settings changed; inside a transaction, only once it commits. */
    public void publishChange() {
        if (cluster != null) {
            cluster.publish(NOTICE);
        }
    }

    public SearchQueryPolicy current() {
        return snapshot().policy();
    }

    public SettingsSnapshot snapshot() {
        SettingsSnapshot current = snapshot;
        if (current == null) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "error.search_config_unavailable");
        return current;
    }

    /**
     * The safety net behind the notices: a node whose last read failed tries again every five seconds, and every node
     * re-reads once a minute in case a notice was lost without the listening connection dropping.
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void refreshIfDue() {
        if (degraded || System.nanoTime() - lastRead > FALLBACK_NANOS) {
            refresh();
        }
    }

    @PostConstruct
    public void refresh() {
        try {
            publishCommitted(repository.current());
            lastRead = System.nanoTime();
        } catch (RuntimeException failure) {
            log.warn("search_policy_refresh_failed error={}", failure.toString());
            degraded = true;
        }
    }

    public synchronized void publishCommitted(SettingsSnapshot candidate) {
        if (snapshot == null || candidate.version() >= snapshot.version()) {
            snapshot = candidate;
            degraded = false;
        }
    }

    public boolean degraded() {
        return degraded;
    }

    public SearchRateBudget effectiveBudget(int ownerPerMinute) {
        return effectiveBudget(current(), ownerPerMinute);
    }

    public EffectiveBudgets effectiveBudgets() {
        return effectiveBudgets(current());
    }

    public EffectiveBudgets effectiveBudgets(SearchQueryPolicy policy) {
        return new EffectiveBudgets(
                effectiveBudget(policy, ownerRateLimits.userPerMinute()),
                effectiveBudget(policy, ownerRateLimits.tokenPerMinute()));
    }

    private static SearchRateBudget effectiveBudget(SearchQueryPolicy policy, int ownerPerMinute) {
        int perMinute = Math.min(policy.requestsPerMinute(), ownerPerMinute);
        return new SearchRateBudget(perMinute, Math.min(policy.burst(), perMinute));
    }

    public record EffectiveBudgets(SearchRateBudget user, SearchRateBudget api) {}
}
