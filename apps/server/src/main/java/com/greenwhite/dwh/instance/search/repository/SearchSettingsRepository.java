package com.greenwhite.dwh.instance.search.repository;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos;
import com.greenwhite.dwh.instance.search.dto.SearchManagementDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SearchSettingsRepository {
    private final JdbcClient jdbc;
    public SearchSettingsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public SettingsSnapshot current() {
        return jdbc.sql("select version,configuration::text from search_settings where id=1")
                .query((rs,row) -> new SettingsSnapshot(rs.getLong("version"),
                        SearchManagementDtos.decodeStored(rs.getString("configuration"), rs.getLong("version"))))
                .single();
    }

    public SettingsSnapshot save(SaveSettingsRequest request, long actorId) {
        Long version = jdbc.sql("""
                update search_settings set version=version+1,configuration=cast(:policy as jsonb),
                    updated_by=:actor,updated_at=clock_timestamp()
                where id=1 and version=:expected returning version
                """).param("expected",request.version()).param("actor",actorId)
                .param("policy", SearchManagementDtos.encodePolicy(request.policy())).query(Long.class).optional()
                .orElseThrow(() -> ApiException.conflict(ErrorCode.CONFLICT, "Search settings changed; refresh and retry"));
        return new SettingsSnapshot(version, request.policy());
    }
}
