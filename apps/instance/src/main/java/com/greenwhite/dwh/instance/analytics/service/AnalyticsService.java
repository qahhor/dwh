package com.greenwhite.dwh.instance.analytics.service;

import com.greenwhite.dwh.instance.analytics.dto.AnalyticsSummaryDto;
import com.greenwhite.dwh.instance.analytics.dto.ProjectDistributionDto;
import com.greenwhite.dwh.instance.analytics.dto.TrendDataPointDto;
import com.greenwhite.dwh.instance.analytics.dto.UserWorkloadDto;
import com.greenwhite.dwh.instance.analytics.repository.AnalyticsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private final AnalyticsRepository repository;

    public AnalyticsService(AnalyticsRepository repository) {
        this.repository = repository;
    }

    public AnalyticsSummaryDto getSummary() {
        return repository.getSummary();
    }

    public List<TrendDataPointDto> getTrends(String range) {
        int days = switch (range != null ? range.toLowerCase() : "7d") {
            case "30d", "month" -> 30;
            case "90d", "quarter" -> 90;
            default -> 7;
        };
        return repository.getTrends(days);
    }

    public List<ProjectDistributionDto> getProjectDistribution() {
        return repository.getProjectDistribution();
    }

    public List<UserWorkloadDto> getUserWorkload() {
        return repository.getUserWorkload();
    }
}
