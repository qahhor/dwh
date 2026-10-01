package com.smartup24.cms.instance.upl.upload;

import com.smartup24.cms.instance.fnd.api.FndActor;
import com.smartup24.cms.instance.fnd.api.FndActorContext;
import com.smartup24.cms.instance.fnd.api.FndJobHandler;
import com.smartup24.cms.instance.fnd.api.FndLoad;
import com.smartup24.cms.instance.fnd.api.FndLoads;
import com.smartup24.cms.instance.fnd.service.FndJobQueries;
import com.smartup24.cms.instance.upl.UplPref;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes applies that were interrupted between steps. The request ({@link UplApplyService}) records the load
 * number and queues {@link UplApplyJob}; the job writes raw to the second database and closes the package. If
 * the job never closed it (the node crashed on every attempt, attempts ran out, the queue stalled), the package
 * would stay "applying" forever (verified with a load number) and the load {@code pending}: a repeated apply
 * would answer 409, and the raw cleanup only sees {@code failed}.
 *
 * <p>The job marks such a load as failed (its raw rows are removed by {@code fnd.load_cleanup}) and
 * the package as "rejected" with code {@link UplApplyService#UPL_PKG_APPLY_INTERRUPTED}, as on
 * a raw write failure: the load has a unique {@code package_ref}, so the same package cannot be
 * applied a second time and the file is uploaded again. An apply older than {@code staleMinutes} counts as interrupted
 * (by default {@value #DEFAULT_STALE_MINUTES}): a live apply of a large file is not touched.
 * An apply whose job is still queued, waiting for a retry or leased is never interrupted, whatever its age: only a job
 * that left the queue without closing the package, or ran out of attempts, makes it one.
 */
@Component
public class UplApplyRecoveryJob implements FndJobHandler {

    public static final String CODE = "upl.apply_recovery";
    /** Longer than the longest apply we expect from a live process. */
    static final int DEFAULT_STALE_MINUTES = 60;

    private static final Logger log = LoggerFactory.getLogger(UplApplyRecoveryJob.class);

    private final UplPackageRepository repo;
    private final FndLoads loads;
    private final FndActorContext actors;
    private final FndJobQueries jobs;

    public UplApplyRecoveryJob(UplPackageRepository repo, FndLoads loads, FndActorContext actors, FndJobQueries jobs) {
        this.repo = repo;
        this.loads = loads;
        this.actors = actors;
        this.jobs = jobs;
    }

    @Override
    public String code() {
        return CODE;
    }

    /** One transaction: the stale packages stay locked until each is closed with its load (the runner opens none). */
    @Override
    @Transactional
    public void run(Map<String, Object> args) {
        int staleMinutes = args.get("staleMinutes") instanceof Number minutes
                ? Math.max(1, minutes.intValue())
                : DEFAULT_STALE_MINUTES;
        FndActor actor = actors.system();
        actors.apply(actor);
        List<PackageRow> stale = repo.lockStaleApplies(staleMinutes);
        // Read after the lock: a job that closes a locked package waits for this transaction
        Set<String> stillQueued = jobs.pendingArgumentValues(UplPref.JOB_APPLY, UplApplyJob.ARG_PACKAGE_ID);
        for (PackageRow row : stale) {
            if (stillQueued.contains(row.publicId().toString())) {
                // Its apply job waits for its turn or a retry, or runs: an old request is not an interrupted one
                continue;
            }
            // The third step closes the package and the load in one transaction: a load that is not pending is not
            // an interruption
            if (loads.find(row.loadId())
                    .filter(load -> FndLoad.PENDING.equals(load.status()))
                    .isEmpty()) {
                continue;
            }
            loads.fail(row.loadId(), UplApplyService.UPL_PKG_APPLY_INTERRUPTED, actor);
            if (repo.markApplyRejected(row.id(), UplApplyService.UPL_PKG_APPLY_INTERRUPTED, Map.of(), null) != 1) {
                throw new IllegalStateException("Пакет " + row.publicId() + " уже не в статусе «проверен»");
            }
            log.warn("Пакет {}: применение прервалось, загрузка {} отмечена неудачной", row.publicId(), row.loadId());
        }
    }
}
