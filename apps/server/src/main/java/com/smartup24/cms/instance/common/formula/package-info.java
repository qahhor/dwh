/**
 * The formula language of live forms (ADR-0042): a small, typed, deterministic expression language — no SQL, no
 * reflection, no loops — parsed and checked once, evaluated by the server and, with the same semantics, by the web.
 * Null-checked by NullAway (plan 10/10, item 1.2): a type that may be null says so with @Nullable.
 */
@NullMarked
package com.smartup24.cms.instance.common.formula;

import org.jspecify.annotations.NullMarked;
