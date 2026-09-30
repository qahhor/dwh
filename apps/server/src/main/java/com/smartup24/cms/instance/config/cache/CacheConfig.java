package com.smartup24.cms.instance.config.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * In-memory caching configuration using Caffeine for frequently queried reference data
 * (task statuses/types, installed modules, navigation items, custom field definitions). Each node keeps its own
 * entries; a change clears the cache on every node of the cluster through {@link CacheInvalidations}.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String TASK_STATUSES_CACHE = "taskStatuses";
    public static final String TASK_TYPES_CACHE = "taskTypes";
    public static final String ACTIVE_MODULES_CACHE = "activeModules";
    public static final String ALL_MODULES_CACHE = "allModules";
    public static final String MODULE_ACTIVE_CACHE = "moduleActive";
    public static final String NAVIGATION_ITEMS_CACHE = "navigationItems";
    public static final String CUSTOM_FIELDS_CACHE = "customFields";

    /**
     * Tells the other nodes of a cluster which cache went stale (plan 10/10, item 3.13, ADR-0025). Not in the migrate
     * step, which serves no requests and must not hold a listening connection.
     */
    @Bean
    @Profile("!migrate")
    public CacheInvalidations cacheInvalidations(
            ObjectProvider<DataSource> dataSource, ObjectProvider<JdbcClient> jdbc) {
        return new CacheInvalidations(dataSource.getIfAvailable(), jdbc.getIfAvailable());
    }

    @Bean
    public CacheManager cacheManager(ObjectProvider<CacheInvalidations> cluster) {
        // Without the cluster bean (the migrate step) the caches stay local to the node.
        CacheInvalidations invalidations = cluster.getIfAvailable(() -> new CacheInvalidations(null, null));
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats());
        cacheManager.setCacheNames(List.of(
                TASK_STATUSES_CACHE,
                TASK_TYPES_CACHE,
                ACTIVE_MODULES_CACHE,
                ALL_MODULES_CACHE,
                MODULE_ACTIVE_CACHE,
                NAVIGATION_ITEMS_CACHE,
                CUSTOM_FIELDS_CACHE));
        return new ClusterCacheManager(cacheManager, invalidations);
    }
}
