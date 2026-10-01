/**
 * The general runtime of the entities (ADR-0032, 6; plan 10/10, item 5.4): one REST endpoint
 * {@code /api/v1/entities/{code}} serves every entity declared with a table, in the fixed order of ADR-0032, 6.3 —
 * rights and If-Match before the transaction, the record read in its scope for update, every problem of the body in
 * one 422, the hooks, the audit, the event and the hooks after the commit. Null-checked by NullAway (plan 10/10, item
 * 1.2).
 */
@NullMarked
package com.smartup24.cms.instance.common.entity.runtime;

import org.jspecify.annotations.NullMarked;
