/**
 * Audit: the partitioned log of data changes, the journal of security events, the history of a record and the archive
 * of old partitions to local disk or S3 storage. Other modules write security events through {@code AuditLogService}.
 * It owns the {@code audit_log*} and {@code security_events} tables and the {@code audit} permission area (ADR-0028).
 * Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.audit;
