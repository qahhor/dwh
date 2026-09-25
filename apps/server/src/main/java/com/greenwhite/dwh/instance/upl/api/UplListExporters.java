package com.greenwhite.dwh.instance.upl.api;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.query.QueryListExporter;
import com.greenwhite.dwh.instance.upl.api.UplPackageDtos.PackageItem;
import com.greenwhite.dwh.instance.upl.api.UplSourceDtos.SourceItem;
import com.greenwhite.dwh.instance.upl.format.UplSourceService;
import com.greenwhite.dwh.instance.upl.upload.UplPackageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** The UPL lists as exports (ADR-0018): the same pages and items the list screens get. */
@Configuration
public class UplListExporters {

    @Bean
    QueryListExporter uplSourcesExporter(UplSourceService sources) {
        return new QueryListExporter() {
            public String code() { return "upl.sources"; }

            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search,
                                      Map<String, String> options) {
                var page = sources.listSources(limit, cursor, filter, sort, search);
                return KeysetPage.of(page.items().stream().map(SourceItem::of).toList(),
                        page.nextCursor(), page.hasMore(), page.totalEstimated());
            }
        };
    }

    @Bean
    QueryListExporter uplPackagesExporter(UplPackageService packages) {
        return new QueryListExporter() {
            public String code() { return "upl.packages"; }

            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search,
                                      Map<String, String> options) {
                var page = packages.list(limit, cursor, filter, sort, search);
                return KeysetPage.of(page.items().stream().map(PackageItem::of).toList(),
                        page.nextCursor(), page.hasMore(), page.totalEstimated());
            }
        };
    }
}
