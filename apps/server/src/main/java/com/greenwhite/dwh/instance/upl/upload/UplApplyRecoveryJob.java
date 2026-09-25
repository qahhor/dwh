package com.greenwhite.dwh.instance.upl.upload;

import com.greenwhite.dwh.instance.fnd.FndActor;
import com.greenwhite.dwh.instance.fnd.FndActors;
import com.greenwhite.dwh.instance.fnd.jobs.FndJobHandler;
import com.greenwhite.dwh.instance.fnd.load.FndLoad;
import com.greenwhite.dwh.instance.fnd.load.FndLoadService;
import com.greenwhite.dwh.instance.upl.upload.UplPackageModel.PackageRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Закрывает применения, прервавшиеся между шагами {@link UplApplyService} (P0 DWH). Первый шаг
 * фиксирует номер загрузки, второй пишет raw во вторую базу, третий закрывает пакет; если процесс
 * упал или база отказала после первого шага, пакет навсегда оставался «проверен» с номером загрузки,
 * а загрузка — {@code pending}: повторное применение отвечало 409, а очистка raw видит только
 * {@code failed}.
 *
 * <p>Задание отмечает такую загрузку неудачной (её строки raw уберёт {@code fnd.load_cleanup}), а
 * пакет — «отклонён системой» с кодом {@link UplApplyService#UPL_PKG_APPLY_INTERRUPTED}, как при
 * сбое записи raw: у загрузки уникальный {@code package_ref}, поэтому тот же пакет второй раз не
 * применить, файл загружают заново. Прерванным считается применение старше {@code staleMinutes}
 * (по умолчанию {@value #DEFAULT_STALE_MINUTES}): живое применение большого файла не трогается.
 */
@Component
public class UplApplyRecoveryJob implements FndJobHandler {

    public static final String CODE = "upl.apply_recovery";
    /** Больше самого долгого применения, которое мы ждём от живого процесса. */
    static final int DEFAULT_STALE_MINUTES = 60;

    private static final Logger log = LoggerFactory.getLogger(UplApplyRecoveryJob.class);

    private final UplPackageRepository repo;
    private final FndLoadService loads;
    private final FndActors actors;

    public UplApplyRecoveryJob(UplPackageRepository repo, FndLoadService loads, FndActors actors) {
        this.repo = repo;
        this.loads = loads;
        this.actors = actors;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public void run(Map<String, Object> args) {
        int staleMinutes = args.get("staleMinutes") instanceof Number minutes
                ? Math.max(1, minutes.intValue()) : DEFAULT_STALE_MINUTES;
        FndActor actor = actors.system();
        actors.apply(actor);
        List<PackageRow> stale = repo.lockStaleApplies(staleMinutes);
        for (PackageRow row : stale) {
            // Третий шаг закрывает пакет и загрузку в одной транзакции: загрузка не pending — не прерывание
            if (loads.find(row.loadId()).filter(load -> FndLoad.PENDING.equals(load.status())).isEmpty()) {
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
