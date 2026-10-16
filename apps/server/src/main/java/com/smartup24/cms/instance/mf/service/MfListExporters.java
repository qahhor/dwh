package com.smartup24.cms.instance.mf.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.query.QueryListExporter;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.service.MdScopeService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The file list as an export (ADR-0018): the viewer's data scope and the "mine" switch, as on the screen. */
@Configuration
public class MfListExporters {

    @Bean
    QueryListExporter mfFilesExporter(MfFileService files, MfFileMetadataService metadata, MdScopeService scopes) {
        return new QueryListExporter() {
            public String code() {
                return "mf.files";
            }

            public boolean visible(Set<String> ids) {
                return metadata.visible(
                        ids.stream().map(UUID::fromString).collect(Collectors.toSet()),
                        scopes.filterForFiles(SecurityContext.getCurrentUserId()));
            }

            public Set<String> options() {
                return Set.of("scope");
            }

            public KeysetPage<?> page(
                    int limit, String cursor, String filter, String sort, String search, Map<String, String> options) {
                boolean onlyMine = "mine".equalsIgnoreCase(options.get("scope"));
                return files.listFiles(
                        SecurityContext.getCurrentUserId(), onlyMine, limit, cursor, filter, sort, search);
            }
        };
    }
}
