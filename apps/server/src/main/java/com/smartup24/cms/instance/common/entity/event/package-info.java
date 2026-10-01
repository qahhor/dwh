/**
 * The event of a changed entity record (ADR-0032, 6.9; plan 10/10, item 5.4): the runtime publishes it in the
 * transaction of the change, and the webhooks and any module listen to it instead of being called by the module that
 * owns the entity. Null-checked by NullAway (plan 10/10, item 1.2).
 */
@NullMarked
package com.smartup24.cms.instance.common.entity.event;

import org.jspecify.annotations.NullMarked;
