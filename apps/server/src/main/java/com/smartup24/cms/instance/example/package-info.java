/**
 * Module {@code example}: the reference modules of the low-code platform and of its cookbook ({@code docs/cookbook};
 * ADR-0032, 9.4; plan 10/10, items 5.7 and 6.6), each one declaration and no screen of its own:
 *
 * <ul>
 *   <li>the reference list — products ({@code ExampleProductsEntity}, {@code example.products}): code, name, unit and
 *       price, archive, import by code, global search;
 *   <li>the document with lines — orders ({@code ExampleOrderEntity}, {@code example.orders}): lines, a total and the
 *       process draft → posted → cancelled;
 *   <li>the document with statuses — purchase requests ({@code ExampleRequestsEntity}, {@code example.requests}): a
 *       reference to a product, the process draft → submitted → approved or rejected, a field right on the resolution
 *       and hooks ({@code ExampleRequestsHooks}).
 * </ul>
 *
 * <p>The general entity runtime serves the records at {@code /api/v1/entities/example.*} and the general screen
 * {@code /e/example.*} edits them. The module owns the {@code ex_*} tables, guards its records with the {@code example}
 * permission area (ADR-0028) and ships switched off in the module registry (ADR-0032, 19, question 3). Module map:
 * {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.example;
