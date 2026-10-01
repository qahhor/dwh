/**
 * Platform infrastructure shared by every module and depending on none of them: the entity model and field registry
 * (ADR-0016, ADR-0019), the error model (ADR-0021), web conventions, JSON columns, retention, versions with a date of
 * validity, the audit actor contract, security helpers and metrics. Not a business module: it owns no business tables,
 * only the platform registries {@code fnd_versioned_tables} and {@code fnd_audit_tables} (ADR-0030). Module map:
 * {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.common;
