/**
 * The process of a document (ADR-0032, 9.2; plan 10/10, item 5.7): its states, the transitions between them and the
 * right each transition needs. A transition is a record action of the general runtime; a state may lock fields and
 * collections, and a terminal state leaves the record only to be read. Null-checked by NullAway (plan 10/10, item 1.2).
 */
@NullMarked
package com.smartup24.cms.platform.api.entity.workflow;

import org.jspecify.annotations.NullMarked;
