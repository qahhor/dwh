/**
 * Reports: list exports to files, built in the background on the job queue and kept for download for a limited time,
 * and the task report. It owns the {@code report_*} tables; its endpoints use forms that the master data and task
 * modules publish to it (ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.report;
