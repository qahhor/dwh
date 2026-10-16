/**
 * Immutable simple forms activated without deployment (ADR-0038).
 *
 * <p>Flyway creates fixed definition and partitioned record tables. Activation joins declarations, permission
 * registration and audit in one transaction. The existing entity runtime serves the records; no runtime DDL.
 */
package com.smartup24.cms.instance.studio;
