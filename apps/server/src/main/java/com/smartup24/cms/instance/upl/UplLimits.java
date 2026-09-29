package com.smartup24.cms.instance.upl;

/**
 * Пределы загрузки файла. Это защита сервера от слишком больших файлов, а не отраслевой норматив:
 * значения одинаковы для всех экземпляров и в настройку ведомства не выносятся.
 */
public final class UplLimits {

    /**
     * Наибольший размер принимаемого файла в мегабайтах: предел загрузки, не норматив. The product limit of one file
     * (FR-FILE-02): since the apply streams (plan 10/10, item 3.9) the size of a file no longer bounds the heap.
     */
    public static final long MAX_FILE_MEGABYTES = 50;

    /** Наибольший размер принимаемого файла в байтах: предел загрузки, не норматив. */
    public static final long MAX_FILE_BYTES = MAX_FILE_MEGABYTES * 1024 * 1024;

    /**
     * Наибольшее число заполненных ячеек в файле: предел загрузки, не норматив. Rows are streamed, so it bounds the
     * time of a parse, not memory: a million rows of ten columns fit.
     */
    public static final long MAX_CELLS = 10000000;

    /** Сколько записей об ошибках сохраняется по пакету: предел загрузки, не норматив. */
    public static final int MAX_STORED_ERRORS = 500;

    /** До скольких знаков обрезается значение ячейки в записи об ошибке: предел загрузки, не норматив. */
    public static final int MAX_VALUE_LENGTH = 200;

    private UplLimits() {}
}
