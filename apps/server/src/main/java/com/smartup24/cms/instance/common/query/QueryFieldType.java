package com.smartup24.cms.instance.common.query;

import static com.smartup24.cms.instance.common.query.QueryOp.BETWEEN;
import static com.smartup24.cms.instance.common.query.QueryOp.CONTAINS;
import static com.smartup24.cms.instance.common.query.QueryOp.EQ;
import static com.smartup24.cms.instance.common.query.QueryOp.GT;
import static com.smartup24.cms.instance.common.query.QueryOp.GTE;
import static com.smartup24.cms.instance.common.query.QueryOp.IN;
import static com.smartup24.cms.instance.common.query.QueryOp.LT;
import static com.smartup24.cms.instance.common.query.QueryOp.LTE;
import static com.smartup24.cms.instance.common.query.QueryOp.NE;
import static com.smartup24.cms.instance.common.query.QueryOp.STARTS_WITH;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** A registry field type: it drives value parsing, the allowed operations and the client's filter editor. */
public enum QueryFieldType {
    TEXT(EnumSet.of(EQ, NE, IN, CONTAINS, STARTS_WITH)),
    NUMBER(EnumSet.of(EQ, NE, IN, GT, GTE, LT, LTE, BETWEEN)),
    DATE(EnumSet.of(EQ, GT, GTE, LT, LTE, BETWEEN)),
    INSTANT(EnumSet.of(GT, GTE, LT, LTE, BETWEEN)),
    /** A time of day ({@code HH:mm[:ss]}), compared as a time (plan 10/10, item 5.0). */
    TIME(EnumSet.of(EQ, NE, GT, GTE, LT, LTE, BETWEEN)),
    BOOLEAN(EnumSet.of(EQ)),
    ENUM(EnumSet.of(EQ, NE, IN));

    private final Set<QueryOp> ops;

    QueryFieldType(Set<QueryOp> ops) {
        this.ops = ops;
    }

    /** The type's operations; the field itself adds {@code empty}/{@code not_empty} if it can be empty. */
    Set<QueryOp> ops() {
        return EnumSet.copyOf(ops);
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
