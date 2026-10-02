/**
 * The rows of a document (ADR-0032, 9.1; plan 10/10, item 5.7): a collection is a child table of an entity whose rows
 * the general runtime reads with the record, checks line by line with addresses like {@code lines[3].qty} and saves in
 * the transaction of the record, raising the record's revision and writing its audit. Null-checked by NullAway (plan
 * 10/10, item 1.2).
 */
@NullMarked
package com.smartup24.cms.instance.common.entity.collection;

import org.jspecify.annotations.NullMarked;
