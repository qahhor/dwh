package com.smartup24.cms.instance.common.entity;

/**
 * A record is about to be deleted (ADR-0041, owner decisions of 2026-10-18): published in the transaction of the
 * deletion, after its checks and hooks and before the row goes, so a listener may still read the record — the webhooks
 * decide which subscriptions scoped to their creator may hear of the deletion. The deletion itself is the
 * {@code EntityChanged} event that follows; a rollback takes both back.
 *
 * @param entity   the entity's code
 * @param recordId the record
 */
public record EntityRemoving(String entity, long recordId) {}
