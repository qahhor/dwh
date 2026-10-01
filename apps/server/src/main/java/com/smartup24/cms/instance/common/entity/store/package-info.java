/**
 * Every statement of the entity runtime (ADR-0032, 6.11; plan 10/10, item 5.4): built only from the declarations —
 * identifiers checked when an entity is declared, values always parameters — in one repository, so the rule "SQL only
 * in repositories" holds and the module boundaries are checked on the declarations. Null-checked by NullAway (plan
 * 10/10, item 1.2).
 */
@NullMarked
package com.smartup24.cms.instance.common.entity.store;

import org.jspecify.annotations.NullMarked;
