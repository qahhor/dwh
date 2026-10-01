package com.smartup24.cms.instance.common.entity.hook;

/** What a save of an entity record does (ADR-0032, 6.5): the hooks and the rules see it. */
public enum EntityOperation {
    CREATE,
    UPDATE,
    /** A declared record action (ADR-0032, 6.7). */
    ACTION
}
