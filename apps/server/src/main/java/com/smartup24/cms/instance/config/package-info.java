/**
 * Application configuration: security and its filters, the error handler, the cluster cache, idempotency of requests,
 * OpenAPI, the retention job, the tick of the job queue, the schema gate, health and system information. Not a
 * business module: it owns only the {@code idempotency_keys} table. Module map:
 * {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.config;
