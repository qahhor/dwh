/**
 * The screen's own filters of each audit list, as typed on screen. The pager
 * reads them at request time, so they stay plain fields changed only by the
 * screen's template handlers.
 */
export class AuditLogFilters {
  table = '';
  event = '';
  rowPk = '';
  user = '';
  from = '';
  to = '';

  private exported: Record<string, string> = {};

  reset(): void {
    this.table = '';
    this.event = '';
    this.rowPk = '';
    this.user = '';
    this.from = '';
    this.to = '';
  }

  /** The filters as request parameters; an empty filter is left out. */
  flat(): Record<string, string | undefined> {
    return {
      tableName: this.table || undefined,
      rowPk: this.rowPk.trim() || undefined,
      event: this.event || undefined,
      userId: this.user.trim() || undefined,
      from: startOfUtcDay(this.from),
      to: endOfUtcDay(this.to),
    };
  }

  /** The filters on screen as export options; the same object while they stay, so the button is not re-rendered. */
  exportOptions(): Record<string, string> {
    this.exported = sameOrNext(this.exported, this.flat());
    return this.exported;
  }
}

export class SecurityEventFilters {
  eventType = '';
  ip = '';
  user = '';
  from = '';
  to = '';

  private exported: Record<string, string> = {};

  reset(): void {
    this.eventType = '';
    this.ip = '';
    this.user = '';
    this.from = '';
    this.to = '';
  }

  flat(): Record<string, string | undefined> {
    return {
      eventType: this.eventType || undefined,
      userId: this.user.trim() || undefined,
      ip: this.ip || undefined,
      from: startOfUtcDay(this.from),
      to: endOfUtcDay(this.to),
    };
  }

  exportOptions(): Record<string, string> {
    this.exported = sameOrNext(this.exported, this.flat());
    return this.exported;
  }
}

function startOfUtcDay(value: string): string | undefined {
  return value ? `${value}T00:00:00.000Z` : undefined;
}

function endOfUtcDay(value: string): string | undefined {
  return value ? `${value}T23:59:59.999Z` : undefined;
}

/** Filters as export options, keeping the previous object while nothing changed. */
function sameOrNext(
  previous: Record<string, string>,
  filters: Record<string, string | undefined>,
): Record<string, string> {
  const next: Record<string, string> = {};
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== '') next[key] = value;
  }
  const same =
    Object.keys(next).length === Object.keys(previous).length &&
    Object.entries(next).every(([key, value]) => previous[key] === value);
  return same ? previous : next;
}
