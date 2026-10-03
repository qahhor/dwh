package com.smartup24.cms.instance.webhook.api;

/**
 * One event a webhook subscription may name (ADR-0032, 6.9): {@code <form>.<event>}, the event being {@code created},
 * {@code updated}, {@code deleted}, {@code archived}, {@code restored} or the code of a record action or transition.
 *
 * @param type   the name a subscription lists ({@code notes.updated})
 * @param entity the code of the entity whose change it is ({@code ms.notes})
 * @param form   the entity's form, the first part of the name ({@code notes})
 */
public record WebhookEventItem(String type, String entity, String form) {}
