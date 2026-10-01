package com.smartup24.cms.instance.warehouse.repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The files the raw rows were read from, as the files module publishes them ({@code mf_pub_files}, ADR-0026): the
 * cross-database check matches every source file id of raw in one query (plan 10/10, item 4.2).
 */
@Repository
public class SourceFileRepository {

    private final JdbcClient jdbc;

    public SourceFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Which of the ids are files: one query for all of them, never one per id. An empty list issues no query. */
    public Set<UUID> existingIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select id from mf_pub_files where id = any(cast(:ids as uuid[]))")
                .param("ids", ids.stream().map(UUID::toString).toArray(String[]::new))
                .query(UUID.class)
                .list());
    }
}
