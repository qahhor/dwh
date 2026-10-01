/**
 * Webhooks: subscriptions of external systems to platform events and the outbox that delivers each event, signed with
 * HMAC-SHA256, to an allowed HTTPS target (FR-COMM-03, NFR-SEC-06). The module was called {@code kwh} before plan
 * 10/10, item 4.3; its {@code kwh_*} tables keep their names (ADR-0020). It guards its endpoints with the
 * {@code webhook} permission area (ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.webhook;
