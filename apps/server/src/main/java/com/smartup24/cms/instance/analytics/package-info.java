/**
 * Analytics: the figures of the dashboard (task summary, trends, workload and project distribution), read from the
 * published views of the task and master data modules (ADR-0026), each over the rows the viewer's data scope shows
 * (ADR-0013). It owns no tables and guards its endpoints with the
 * {@code analytics} permission area (ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.analytics;
