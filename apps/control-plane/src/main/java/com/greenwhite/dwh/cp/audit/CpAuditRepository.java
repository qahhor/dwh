package com.greenwhite.dwh.cp.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CpAuditRepository {

    private final JdbcClient jdbc;

    public CpAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actorType,
                       String actorId,
                       String action,
                       String entityType,
                       String entityId) {
        jdbc.sql("""
                        insert into cp_audit_events(
                            actor_type, actor_id, action, entity_type, entity_id)
                        values (:actorType, :actorId, :action, :entityType, :entityId)
                        """)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .update();
    }
}
