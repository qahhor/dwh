package com.smartup24.cms.instance.fnd.jobs;

import java.util.Map;

/**
 * Обработчик задания основы (02 п.15; 18 п.4). Расписание и очередь — таблицы {@code fnd_job_*};
 * планировщика Spring в основе нет: задания снимает {@link FndJobRunner}, которого вызывает
 * шаг поставки или воркер экземпляра.
 *
 * <p>The runner calls a handler with no transaction open (plan 10/10, item 3.8): a handler that needs atomicity opens
 * its own short transactions, and long work (reading a file, writing pg-dwh) holds none. A handler may run more than
 * once for one job — after a failure, or when its node died mid-run — so it checks the state it is about to change.
 */
public interface FndJobHandler {

    /** Код обработчика, как он записан в {@code fnd_job_schedule.handler}. */
    String code();

    /** Выполняет задание; исключение помечает запуск неудачным и остаётся в {@code fnd_job_runs.error}. */
    void run(Map<String, Object> args);
}
