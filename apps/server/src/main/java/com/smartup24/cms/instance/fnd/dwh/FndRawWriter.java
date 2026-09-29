package com.smartup24.cms.instance.fnd.dwh;

import java.util.List;
import java.util.UUID;

/**
 * Единственный вход в слой {@code raw} второй базы (11 п.6–7; 18 п.14; AC-33, AC-34).
 * Методов правки и удаления у фасада нет: {@code raw} неизменяем, новая версия данных — новая
 * загрузка (13 инв.4). Удаление строк неудачной загрузки — дело задания {@code fnd.load_cleanup}.
 */
public interface FndRawWriter {

    /**
     * Пишет строки одной загрузки одной транзакцией: либо все, либо ни одной (AC-34).
     *
     * @param loadId       версия загрузки в статусе {@code pending}; иначе отказ
     * @param sourceFileId файл каркаса ({@code mf_files.id}), из которого прочитаны строки
     * @param rows         строки как прочитаны; исключение источника доходит до вызывающего
     */
    default void write(long loadId, UUID sourceFileId, Iterable<FndRawRow> rows) {
        copy(loadId, sourceFileId, sink -> rows.forEach(sink));
    }

    /**
     * Streams the rows of one load into raw as the source emits them, in one transaction: all or none (AC-34). The
     * writer keeps no rows, so the size of a load does not bound it (plan 10/10, item 3.9).
     *
     * @param loadId       a load in status {@code pending}; any other is refused
     * @param sourceFileId the file ({@code mf_files.id}) the rows were read from
     * @param rows         the rows as read; an exception of the source reaches the caller ({@link java.io.IOException}
     *                     as {@link java.io.UncheckedIOException})
     * @return how many rows the database took
     */
    long copy(long loadId, UUID sourceFileId, FndRawSource rows);

    /** How many rows a load has in raw: a reconciliation counts them and never reads them back. */
    long count(long loadId);

    /** Строки загрузки как записаны — без типизации и изменений (AC-33). */
    List<FndRawRow> read(long loadId);
}
