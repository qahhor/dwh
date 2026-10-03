/**
 * What an entity's author writes besides its declaration (ADR-0032, 6.5–6.7; plan 10/10, item 5.4): the hooks of a
 * save, a delete and a commit, the cross-field rules and the handlers of record actions. The runtime calls them in its
 * fixed order; none of them changes that order. Null-checked by NullAway (plan 10/10, item 1.2).
 */
@NullMarked
package com.smartup24.cms.platform.api.entity.hook;

import org.jspecify.annotations.NullMarked;
