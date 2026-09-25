package com.greenwhite.dwh.instance.mf.service;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.query.QueryListExporter;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/** The file list as an export (ADR-0018): the viewer's data scope and the "mine" switch, as on the screen. */
@Configuration
public class MfListExporters {

    @Bean
    QueryListExporter mfFilesExporter(MfFileService files) {
        return new QueryListExporter() {
            public String code() { return "mf.files"; }

            public Set<String> options() { return Set.of("scope"); }

            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search,
                                      Map<String, String> options) {
                boolean onlyMine = "mine".equalsIgnoreCase(options.get("scope"));
                return files.listFiles(SecurityContext.getCurrentUserId(), onlyMine, limit, cursor, filter, sort, search);
            }
        };
    }
}
