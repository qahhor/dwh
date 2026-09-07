package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.search.repository.SearchProjectionReader;
import com.greenwhite.dwh.instance.search.typesense.TypesenseClient;
import org.springframework.stereotype.Component;

/** Conservative measured guard, not a capacity guarantee. Never invoked while holding the publication barrier. */
@Component
public class SearchStoragePreflight {
    private final TypesenseClient client;
    private final SearchProjectionReader reader;
    public SearchStoragePreflight(TypesenseClient client,SearchProjectionReader reader) { this.client=client;this.reader=reader; }
    public void requireSpace() {
        var metadata=client.observeDependency();
        Long total=metadata.installationDiskTotalBytes(), used=metadata.installationDiskUsedBytes();
        if (!metadata.healthy() || total==null || used==null || total<=0 || used>total)
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,"SEARCH_STORAGE_UNAVAILABLE");
        long reserve;
        try { reserve=Math.max(64L*1024*1024,Math.multiplyExact(2,reader.estimateSerializedBytes())); }
        catch (ArithmeticException overflow) { throw new ApiException(ErrorCode.CONFLICT,"INSUFFICIENT_SEARCH_STORAGE"); }
        if (total-used<reserve) throw new ApiException(ErrorCode.CONFLICT,"INSUFFICIENT_SEARCH_STORAGE");
    }
}
