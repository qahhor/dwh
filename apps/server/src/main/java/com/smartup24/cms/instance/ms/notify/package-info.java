/**
 * Notifications ({@code ms} prefix: messaging and services): the inbox of a user, delivery preferences, the outbox that
 * sends e-mail, SMS and messenger messages through providers, server-sent events and announcements. It owns the
 * {@code ms_notification*} and {@code ms_announcement*} tables and the {@code notify} permission area (ADR-0028).
 * Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.ms.notify;
