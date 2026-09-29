package com.smartup24.cms.instance.fnd.units;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import java.time.LocalDate;
import java.util.Map;

/**
 * Коэффициента на дату нет (13 инв.3; AC-21): пересчёт не выполняется и значение не возвращается
 * ни в каком виде — «значение по памяти» запрещено. Обратный коэффициент и цепочки не выводятся (доп.5).
 * В ответе API — конфликт с данными справочника единиц, текст {@code error.fnd.coefficient_missing}.
 */
public class FndCoefficientMissingException extends ApiException {

    private final String fromUnit;
    private final String toUnit;
    private final LocalDate date;

    public FndCoefficientMissingException(String fromUnit, String toUnit, LocalDate date) {
        super(
                ErrorCode.CONFLICT,
                "error.fnd.coefficient_missing",
                Map.of("from", String.valueOf(fromUnit), "to", String.valueOf(toUnit), "date", String.valueOf(date)));
        this.fromUnit = fromUnit;
        this.toUnit = toUnit;
        this.date = date;
    }

    public String fromUnit() {
        return fromUnit;
    }

    public String toUnit() {
        return toUnit;
    }

    public LocalDate date() {
        return date;
    }
}
