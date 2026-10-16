package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.instance.common.query.LiveQueryListSource;
import com.smartup24.cms.instance.common.query.QueryList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Derives live lists from the same definitions the entity runtime reads (ADR-0038). */
@Component
public class LiveEntityLists implements LiveQueryListSource {
    private final LiveEntities entities;

    public LiveEntityLists(LiveEntities entities) {
        this.entities = entities;
    }

    @Override
    public List<QueryList> lists() {
        return entities.all().stream().map(EntityLists::queryList).toList();
    }
}
