package com.smartup24.cms.instance.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityEnums;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;

class CacheConfigTest {

    private final CacheConfig cacheConfig = new CacheConfig();

    @Test
    @DisplayName("CacheManager должен регистрировать все необходимые кэши для справочников и навигации")
    void shouldRegisterAllDeclaredCaches() {
        CacheManager cacheManager = cacheConfig.cacheManager(localOnly());
        assertThat(cacheManager).isNotNull();

        assertThat(cacheManager.getCacheNames())
                .containsExactlyInAnyOrder(
                        CacheConfig.TASK_STATUSES_CACHE,
                        CacheConfig.TASK_TYPES_CACHE,
                        CacheConfig.ACTIVE_MODULES_CACHE,
                        CacheConfig.ALL_MODULES_CACHE,
                        CacheConfig.MODULE_ACTIVE_CACHE,
                        CacheConfig.NAVIGATION_ITEMS_CACHE,
                        CacheConfig.CUSTOM_FIELDS_CACHE,
                        EntityEnums.CACHE);
    }

    @Test
    @DisplayName("Кэш должен сохранять и возвращать значения, а также поддерживать очистку")
    void shouldStoreRetrieveAndEvictCachedEntries() {
        CacheManager cacheManager = cacheConfig.cacheManager(localOnly());
        Cache cache = cacheManager.getCache(CacheConfig.TASK_STATUSES_CACHE);
        assertThat(cache).isNotNull();

        cache.put("all", "dummy-status-list");
        assertThat(cache.get("all", String.class)).isEqualTo("dummy-status-list");

        cache.clear();
        assertThat(cache.get("all")).isNull();
    }

    @Test
    @DisplayName("3.13: the migrate step holds no listening connection, and the caches then stay local")
    void migrateStepHasNoClusterListener() throws NoSuchMethodException {
        Method bean = CacheConfig.class.getMethod("cacheInvalidations", ObjectProvider.class, ObjectProvider.class);
        assertThat(bean.getAnnotation(Profile.class).value()).containsExactly("!migrate");

        CacheManager cacheManager = cacheConfig.cacheManager(localOnly());
        cacheManager.getCache(CacheConfig.TASK_TYPES_CACHE).clear();
        assertThat(cacheManager.getCacheNames()).contains(CacheConfig.TASK_TYPES_CACHE);
    }

    /** No cluster bean in the context: what the migrate step sees. */
    private static ObjectProvider<CacheInvalidations> localOnly() {
        return new DefaultListableBeanFactory().getBeanProvider(CacheInvalidations.class);
    }
}
