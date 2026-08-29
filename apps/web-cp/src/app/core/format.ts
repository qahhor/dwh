const DATE_TIME = new Intl.DateTimeFormat('ru-RU', {
  day: '2-digit', month: '2-digit', year: 'numeric',
  hour: '2-digit', minute: '2-digit'
});

/** Дата-время в локали оператора; прочерк, если сервер вернул null. */
export function dt(iso: string | null | undefined): string {
  return iso ? DATE_TIME.format(new Date(iso)) : '—';
}

/** «3 мин назад» — для колонки последнего heartbeat. */
export function ago(iso: string | null | undefined): string {
  if (!iso) {
    return 'никогда';
  }
  const sec = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (sec < 60) return `${sec} с назад`;
  if (sec < 3600) return `${Math.floor(sec / 60)} мин назад`;
  if (sec < 86400) return `${Math.floor(sec / 3600)} ч назад`;
  return `${Math.floor(sec / 86400)} дн назад`;
}

/**
 * Текст ошибки для оператора. Сервер отдаёт RFC 9457 problem+json,
 * поле detail несёт причину; на остальные случаи — по коду статуса.
 */
export function errorText(e: unknown, fallback = 'Операция не выполнена'): string {
  const err = e as { status?: number; error?: { detail?: string; message?: string } };
  const detail = err?.error?.detail ?? err?.error?.message;
  if (detail) {
    return detail;
  }
  switch (err?.status) {
    case 0: return 'Control plane недоступен';
    case 401: return 'Сессия истекла — войдите заново';
    case 403: return 'Недостаточно прав для этого действия';
    case 404: return 'Объект не найден';
    case 409: return 'Такой объект уже существует';
    default: return fallback;
  }
}
