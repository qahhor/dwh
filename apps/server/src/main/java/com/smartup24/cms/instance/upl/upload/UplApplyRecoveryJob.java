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
 * Закрывает применения, прервавшиеся между шагами (P0 DWH). Запрос ({@link UplApplyService}) фиксирует номер
 * загрузки и ставит {@link UplApplyJob}, задание пишет raw во вторую базу и закрывает пакет; если задание так и не
 * закрыло его (узел падал на каждой попытке, попытки кончились, очередь стояла), пакет навсегда оставался бы
 * «применяется» (проверен с номером загрузки), а загрузка — {@code pending}: повторное применение отвечало 409, а
 * очистка raw видит только {@code failed}.
 *
 * <p>Задание отмечает такую загрузку неудачной (её строки raw уберёт {@code fnd.load_cleanup}), а
 * пакет — «отклонён системой» с кодом {@link UplApplyService#UPL_PKG_APPLY_INTERRUPTED}, как при
 * сбое записи raw: у загрузки уникальный {@code package_ref}, поэтому тот же пакет второй раз не
 * применить, файл загружают заново. Прерванным считается применение старше {@code staleMinutes}
 * (по умолчанию {@value #DEFAULT_STALE_MINUTES}): живое применение большого файла не трогается.
 * An apply whose job is still queued, waiting for a retry or leased is never interrupted, whatever its age: only a job
 * that left the queue without closing the package, or ran out of attempts, makes it one.
 */
@Component
public class UplApplyRecoveryJob implements FndJobHandler {

    public static final String CODE = "upl.apply_recovery";
    /** Больше самого долгого применения, которое мы ждём от живого процесса. */
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
            // Третий шаг закрывает пакет и загрузку в одной транзакции: загрузка не pending — не прерывание
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
