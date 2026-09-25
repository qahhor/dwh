/* Our code: reading and listing times of day for smt-time-picker. */

/** A time of day as the field stores it: `HH:mm`, 24-hour. */
export type SMTTime = string;

const MINUTES_IN_DAY = 24 * 60;

/**
 * What a person typed, as `HH:mm`, or null when it is not a time.
 * Accepts `9`, `09`, `930`, `0930`, `9:30`, `9.30`, `9-30`, `9 30` and `21:5`
 * (read as 21:05) — the shapes people type without thinking about a mask.
 */
export function parseTime(text: string): SMTTime | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  let hours: number;
  let minutes: number;
  const parts = trimmed.split(/[:.\-\s]+/);
  if (parts.length === 2 && /^\d{1,2}$/.test(parts[0]) && /^\d{1,2}$/.test(parts[1])) {
    hours = Number(parts[0]);
    minutes = Number(parts[1]);
  } else if (parts.length === 1 && /^\d{1,4}$/.test(trimmed)) {
    const digits = trimmed;
    if (digits.length <= 2) {
      hours = Number(digits);
      minutes = 0;
    } else {
      hours = Number(digits.slice(0, digits.length - 2));
      minutes = Number(digits.slice(-2));
    }
  } else {
    return null;
  }
  if (hours > 23 || minutes > 59) return null;
  return formatTime(hours * 60 + minutes);
}

/** Minutes after midnight for a stored time, or null. */
export function timeToMinutes(time: SMTTime | null | undefined): number | null {
  if (!time || !/^\d{2}:\d{2}$/.test(time)) return null;
  const [hours, minutes] = time.split(':').map(Number);
  return hours < 24 && minutes < 60 ? hours * 60 + minutes : null;
}

export function formatTime(totalMinutes: number): SMTTime {
  const minutes = ((totalMinutes % MINUTES_IN_DAY) + MINUTES_IN_DAY) % MINUTES_IN_DAY;
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

/** Every `step` minutes from `min` to `max` inclusive, the choices the list offers. */
export function timeSlots(step: number, min: SMTTime | null, max: SMTTime | null): SMTTime[] {
  const every = Number.isFinite(step) && step >= 1 ? Math.floor(step) : 30;
  const from = timeToMinutes(min) ?? 0;
  const to = timeToMinutes(max) ?? MINUTES_IN_DAY - 1;
  const slots: SMTTime[] = [];
  for (let minute = Math.ceil(from / every) * every; minute <= to; minute += every) slots.push(formatTime(minute));
  return slots;
}

/** Whether a time lies within the optional bounds. */
export function withinBounds(time: SMTTime, min: SMTTime | null, max: SMTTime | null): boolean {
  const value = timeToMinutes(time);
  if (value === null) return false;
  const from = timeToMinutes(min);
  const to = timeToMinutes(max);
  return (from === null || value >= from) && (to === null || value <= to);
}

/** The slot at or right after a time, so the list opens where the value is. */
export function nearestSlot(slots: readonly SMTTime[], time: SMTTime | null): number {
  const value = timeToMinutes(time);
  if (value === null || slots.length === 0) return -1;
  const index = slots.findIndex(slot => (timeToMinutes(slot) ?? 0) >= value);
  return index >= 0 ? index : slots.length - 1;
}
