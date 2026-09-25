package com.greenwhite.dwh.instance.common.query;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static com.greenwhite.dwh.instance.common.query.QueryOp.BETWEEN;
import static com.greenwhite.dwh.instance.common.query.QueryOp.CONTAINS;
import static com.greenwhite.dwh.instance.common.query.QueryOp.EQ;
import static com.greenwhite.dwh.instance.common.query.QueryOp.GT;
import static com.greenwhite.dwh.instance.common.query.QueryOp.GTE;
import static com.greenwhite.dwh.instance.common.query.QueryOp.IN;
import static com.greenwhite.dwh.instance.common.query.QueryOp.LT;
import static com.greenwhite.dwh.instance.common.query.QueryOp.LTE;
import static com.greenwhite.dwh.instance.common.query.QueryOp.NE;
import static com.greenwhite.dwh.instance.common.query.QueryOp.STARTS_WITH;

/** Тип поля реестра: от него зависят разбор значения, допустимые операции и редактор фильтра на клиенте. */
public enum QueryFieldType {
    TEXT(EnumSet.of(EQ, NE, IN, CONTAINS, STARTS_WITH)),
    NUMBER(EnumSet.of(EQ, NE, IN, GT, GTE, LT, LTE, BETWEEN)),
    DATE(EnumSet.of(EQ, GT, GTE, LT, LTE, BETWEEN)),
    INSTANT(EnumSet.of(GT, GTE, LT, LTE, BETWEEN)),
    BOOLEAN(EnumSet.of(EQ)),
    ENUM(EnumSet.of(EQ, NE, IN));

    private final Set<QueryOp> ops;

    QueryFieldType(Set<QueryOp> ops) {
        this.ops = ops;
    }

    /** Операции типа; {@code empty}/{@code not_empty} добавляет само поле, если оно может быть пустым. */
    Set<QueryOp> ops() {
        return EnumSet.copyOf(ops);
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
