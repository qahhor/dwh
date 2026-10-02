package com.smartup24.cms.instance.analytics.service;

import com.smartup24.cms.instance.analytics.dto.AnalyticsSummaryDto;
import com.smartup24.cms.instance.analytics.dto.ProjectDistributionDto;
import com.smartup24.cms.instance.analytics.dto.TrendDataPointDto;
import com.smartup24.cms.instance.analytics.dto.UserWorkloadDto;
import com.smartup24.cms.instance.analytics.repository.AnalyticsRepository;
import com.smartup24.cms.instance.analytics.repository.AnalyticsRepository.Scope;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.md.service.MdScopeService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard figures of one viewer: every count, rate and row is computed only over the tasks, projects and users
 * the viewer's data scope shows, with the same predicates as their lists (ADR-0013, 2.5); the rule ALL keeps the
 * figures of the whole installation.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private final AnalyticsRepository repository;
    private final MdScopeService scopes;

    public AnalyticsService(AnalyticsRepository repository, MdScopeService scopes) {
        this.repository = repository;
        this.scopes = scopes;
    }

    public AnalyticsSummaryDto getSummary(@Nullable Long viewerId) {
        return repository.getSummary(scopeOf(viewerId));
    }

    public List<TrendDataPointDto> getTrends(String range, @Nullable Long viewerId) {
        int days = switch (range != null ? range.toLowerCase() : "7d") {
            case "30d", "month" -> 30;
            case "90d", "quarter" -> 90;
            default -> 7;
        };
        return repository.getTrends(days, scopeOf(viewerId));
    }

    public List<ProjectDistributionDto> getProjectDistribution(@Nullable Long viewerId) {
        return repository.getProjectDistribution(scopeOf(viewerId));
    }

    public List<UserWorkloadDto> getUserWorkload(@Nullable Long viewerId) {
        return repository.getUserWorkload(scopeOf(viewerId));
    }

    /**
     * The viewer's restrictions. Without a viewer the scope service answers "unrestricted", so an anonymous call is
     * refused here rather than shown the whole installation.
     */
    private Scope scopeOf(@Nullable Long viewerId) {
        if (viewerId == null) {
            throw ApiException.unauthorized("error.auth.not_signed_in");
        }
        return new Scope(
                scopes.filterForTasks(viewerId),
                scopes.filterForProjects(viewerId),
                scopes.filterForUsers(viewerId, "u.id"));
    }
}
