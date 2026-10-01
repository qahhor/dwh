package com.smartup24.cms.instance.fnd.api;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Rows of one load as their reader produces them (plan 10/10, item 3.9): the writer hands a sink, the source pushes
 * each row into it and forgets it. Nothing between a file parser and {@code COPY} holds more than the row in hand, so a
 * million-row file costs the memory of one row and a copy buffer.
 */
@FunctionalInterface
public interface FndRawSource {

    /**
     * Pushes the rows into {@code sink} in write order; an exception of the source or of the sink stops the write, and
     * nothing of it stays in raw.
     */
    void emit(Consumer<FndRawRow> sink) throws IOException;
}
