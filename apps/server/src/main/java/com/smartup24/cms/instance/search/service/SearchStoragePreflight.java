package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.search.repository.SearchProjectionReader;
import com.smartup24.cms.instance.search.typesense.TypesenseHealth;
import org.springframework.stereotype.Component;

/** Conservative measured guard, not a capacity guarantee. Never invoked while holding the publication barrier. */
@Component
public class SearchStoragePreflight {
    private final TypesenseHealth health;
    private final SearchProjectionReader reader;

    public SearchStoragePreflight(TypesenseHealth health, SearchProjectionReader reader) {
        this.health = health;
        this.reader = reader;
    }

    public void requireSpace() {
        var metadata = health.observeDependency();
        Long total = metadata.installationDiskTotalBytes(), used = metadata.installationDiskUsedBytes();
        if (!metadata.healthy() || total == null || used == null || total <= 0 || used > total)
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "error.search.storage_unavailable");
        long reserve;
        try {
            reserve = Math.max(64L * 1024 * 1024, Math.multiplyExact(2, reader.estimateSerializedBytes()));
        } catch (ArithmeticException overflow) {
            throw new ApiException(ErrorCode.CONFLICT, "error.search.storage_insufficient");
        }
        if (total - used < reserve) throw new ApiException(ErrorCode.CONFLICT, "error.search.storage_insufficient");
    }
}
