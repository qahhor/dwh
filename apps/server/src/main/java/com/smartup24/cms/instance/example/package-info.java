/**
 * Module {@code example}: the reference "document with lines and statuses" of the low-code platform (ADR-0032, 9.4;
 * plan 10/10, item 5.7) — orders with lines, a total and the process draft → posted → cancelled, built from one
 * declaration ({@code ExampleOrderEntity}) and no screen of its own: the general entity runtime serves the records at
 * {@code /api/v1/entities/example.orders} and the general screen {@code /e/example.orders} edits the lines and runs the
 * transitions. It owns the {@code ex_*} tables, guards its records with the {@code example} permission area (ADR-0028)
 * and ships switched off in the module registry (ADR-0032, 19, question 3). It is the reference of the cookbook for a
 * document. Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.example;
