/**
 * Jobs: the shared background job queue of the product (lease, retry, schedule and run history) on which reports,
 * uploads and the warehouse put their work through {@code jobs.api}. It depends on no other business module and owns
 * the {@code fnd_job_*} tables, which keep their names (ADR-0020, ADR-0030). Module map:
 * {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.jobs;
