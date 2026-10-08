package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.actor.AuditActorContext;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.jobs.api.JobQueue;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.api.UplPackageDtos.PackageItem;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoads;
import com.smartup24.cms.platform.api.actor.AuditActor;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies a "verified" package, asynchronous since plan 10/10, item 3.9: the request opens
 * the load of the foundation, turns the package "applying" and queues {@link UplApplyJob}, which streams the rows
 * into raw and closes the package. The request holds one short OLTP transaction and starts no long work, so it answers
 * at once and an Idempotency-Key replays it (item 3.12).
 */
@Service
public class UplApplyService {

    /** The number of rows in raw differs from the package, or the package counters do not add up. */
    public static final String UPL_PKG_RECONCILIATION = "UPL_PKG_RECONCILIATION";
    /** The package rows were not written to raw. */
    public static final String UPL_PKG_RAW_WRITE_FAILED = "UPL_PKG_RAW_WRITE_FAILED";
    /** Apply was interrupted between steps (process crash, database failure); the recovery job closed the package. */
    public static final String UPL_PKG_APPLY_INTERRUPTED = "UPL_PKG_APPLY_INTERRUPTED";

    private final UplPackageService packages;
    private final UplPackageRepository repo;
    private final WarehouseLoads loads;
    private final AuditActorContext actors;
    private final JobQueue jobs;
    private final TransactionTemplate tx;

    /** Requests the apply as {@link #request} does and answers the package as the API shows it (plan 10/10, 3.2). */
    public PackageItem requestItem(String publicId, long userId) {
        return PackageItem.of(request(publicId, userId));
    }

    public UplApplyService(
            UplPackageService packages,
            UplPackageRepository repo,
            WarehouseLoads loads,
            AuditActorContext actors,
            JobQueue jobs,
            TransactionTemplate tx) {
        this.packages = packages;
        this.repo = repo;
        this.loads = loads;
        this.actors = actors;
        this.jobs = jobs;
        this.tx = tx;
    }

    /**
     * Queues the apply: opens a foundation load, turns the package "applying" and queues the job, all
     * in one transaction (with an Idempotency-Key, the request transaction). Not "verified" gives 409,
     * no accepted rows gives 409, no package gives 404.
     *
     * @return the package "applying": the status resource the client polls until it is applied or rejected
     */
    public PackageRow request(String publicId, long userId) {
        UUID id = packages.get(publicId).publicId();
        AuditActor actor = actors.user(userId);
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
                throw new IllegalStateException("Package " + row.publicId() + " is no longer validated");
            }
            jobs.enqueueOnce(UplPref.JOB_APPLY, UplApplyJob.args(row.publicId(), userId));
            return repo.findById(row.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Package " + id + " disappeared while its apply was being queued"));
        });
    }
}
