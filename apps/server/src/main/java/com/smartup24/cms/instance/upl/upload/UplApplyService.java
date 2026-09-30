package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.fnd.FndActor;
import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.jobs.FndJobRunner;
import com.smartup24.cms.instance.fnd.load.FndLoadService;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.api.UplPackageDtos.PackageItem;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Применение пакета «проверен» (контракт И6, раздел 6), asynchronous since plan 10/10, item 3.9: the request opens
 * the load of the foundation, turns the package «применяется» and queues {@link UplApplyJob}, which streams the rows
 * into raw and closes the package. The request holds one short OLTP transaction and starts no long work, so it answers
 * at once and an Idempotency-Key replays it (item 3.12).
 */
@Service
public class UplApplyService {

    /** Строк в raw не столько, сколько в пакете, или счётчики пакета не сходятся. */
    public static final String UPL_PKG_RECONCILIATION = "UPL_PKG_RECONCILIATION";
    /** Строки пакета не записаны в raw. */
    public static final String UPL_PKG_RAW_WRITE_FAILED = "UPL_PKG_RAW_WRITE_FAILED";
    /** Применение прервалось между шагами (падение процесса, сбой базы) — пакет закрыло задание восстановления. */
    public static final String UPL_PKG_APPLY_INTERRUPTED = "UPL_PKG_APPLY_INTERRUPTED";

    private final UplPackageService packages;
    private final UplPackageRepository repo;
    private final FndLoadService loads;
    private final FndActors actors;
    private final FndJobRunner jobs;
    private final TransactionTemplate tx;

    /** Requests the apply as {@link #request} does and answers the package as the API shows it (plan 10/10, 3.2). */
    public PackageItem requestItem(String publicId, long userId) {
        return PackageItem.of(request(publicId, userId));
    }

    public UplApplyService(
            UplPackageService packages,
            UplPackageRepository repo,
            FndLoadService loads,
            FndActors actors,
            FndJobRunner jobs,
            TransactionTemplate tx) {
        this.packages = packages;
        this.repo = repo;
        this.loads = loads;
        this.actors = actors;
        this.jobs = jobs;
        this.tx = tx;
    }

    /**
     * Ставит применение в очередь: открывает загрузку основы, переводит пакет в «применяется» и ставит задание — всё
     * одной транзакцией (с Idempotency-Key — транзакцией запроса). Не «проверен» — 409, нет принятых строк — 409, нет
     * пакета — 404.
     *
     * @return the package «применяется»: the status resource the client polls until it is applied or rejected
     */
    public PackageRow request(String publicId, long userId) {
        UUID id = packages.get(publicId).publicId();
        FndActor actor = actors.user(userId);
        return tx.execute(status -> {
            PackageRow row = repo.lockByPublicId(id)
                    .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "error.upl.pkg_not_found"));
            if (!UplPackageModel.VERIFIED.equals(row.status()) || row.loadId() != null) {
                throw ApiException.conflict(ErrorCode.CONFLICT, "error.upl.pkg_not_verified");
            }
            if (row.rowsAccepted() == null || row.rowsAccepted() == 0) {
                throw ApiException.conflict(ErrorCode.CONFLICT, "error.upl.pkg_nothing_to_apply");
            }
            long loadId = loads.begin(
                    row.sourceCode(),
                    row.publicId(),
                    row.periodFrom(),
                    row.periodTo(),
                    String.valueOf(row.formatVersion()),
                    actor);
            actors.apply(actor);
            if (repo.setLoadId(row.id(), loadId) != 1) {
                throw new IllegalStateException("Пакет " + row.publicId() + " уже не в статусе «проверен»");
            }
            jobs.enqueueOnce(UplPref.JOB_APPLY, UplApplyJob.args(row.publicId(), userId));
            return repo.findById(row.id())
                    .orElseThrow(() -> new IllegalStateException("Пакет " + id + " пропал при постановке применения"));
        });
    }
}
