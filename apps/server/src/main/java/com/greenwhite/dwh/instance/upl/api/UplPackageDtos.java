package com.greenwhite.dwh.instance.upl.api;

import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.ErrorRow;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.ErrorsView;
import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.PackageRow;
import com.greenwhite.dwh.instance.upl.upload.UplPackageQuery;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ответы API загрузок файлов (контракт И5, раздел 3). Внутренние числовые идентификаторы наружу не выходят. */
public final class UplPackageDtos {

    private UplPackageDtos() {
    }

    /** Пакет загрузки: счётчики и причина отказа пусты, пока файл не разобран. */
    public record PackageItem(UUID id, long sourceId, String sourceCode, String sourceName, int formatVersion,
                              LocalDate periodFrom, LocalDate periodTo, String fileName, long fileSizeBytes,
                              String uploadedBy, Instant uploadedAt, String status,
                              Integer rowsTotal, Integer rowsAccepted, Integer rowsRejected, Integer errorsTotal,
                              String rejectCode, Map<String, Object> rejectParams, Long loadId, Integer rawRows) {

        /** Who uploaded names a person; every response of a package follows the list's field right (ADR-0016, 2.9). */
        private static final QueryField UPLOADER = UplPackageQuery.LIST.field("uploadedBy").orElseThrow();

        public static PackageItem of(PackageRow row) {
            return new PackageItem(row.publicId(), row.sourceId(), row.sourceCode(), row.sourceName(),
                    row.formatVersion(), row.periodFrom(), row.periodTo(), row.fileName(), row.fileSizeBytes(),
                    UPLOADER.visibleToViewer() ? row.uploadedBy() : null, row.uploadedAt(), row.status(),
                    row.rowsTotal(), row.rowsAccepted(), row.rowsRejected(), row.errorsTotal(),
                    row.rejectCode(), row.rejectParams(), row.loadId(), row.rawRows());
        }
    }

    /** Запись об ошибке: {@code rowNo == null} — расхождение с анкетой, иначе адрес ячейки. */
    public record ErrorItem(String sheet, Integer rowNo, String columnName, String value, String code,
                            Map<String, Object> params) {

        public static ErrorItem of(ErrorRow row) {
            return new ErrorItem(row.sheet(), row.rowNo(), row.columnName(), row.cellValue(), row.code(),
                    row.params());
        }
    }

    /** Ошибки пакета: {@code total} — сколько найдено всего, {@code shown} — сколько сохранено и отдано. */
    public record PackageErrors(int total, int shown, List<ErrorItem> items) {

        public static PackageErrors of(ErrorsView view) {
            List<ErrorItem> items = view.items().stream().map(ErrorItem::of).toList();
            return new PackageErrors(view.total(), items.size(), items);
        }
    }
}
