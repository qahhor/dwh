/**
 * Tasks ({@code ms} prefix: messaging and services): task types and statuses, projects with their members, tasks, their
 * comments and files. The types, statuses, projects and tasks are entities of the general runtime (ADR-0032, 8) with the
 * module's hooks and record actions; the participants, comments and files keep endpoints of their own. It owns the
 * {@code ms_task*} tables, publishes the {@code ms_task_pub_*} read views and guards its endpoints with the {@code tasks}
 * permission area (ADR-0026, ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.ms.task;
