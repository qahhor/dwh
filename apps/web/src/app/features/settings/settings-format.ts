/** Translates a key; the formatters fall back to a Latin unit when a key is missing. */
type Translate = (key: string) => string;

/** "720 h (30 d)": a session lifetime in hours with its length in days, or '' for no valid value. */
export function formatSessionHours(hours: string | number | undefined, translate: Translate): string {
  if (hours === undefined || hours === '') return '';
  const num = Number(hours);
  if (!Number.isFinite(num) || num <= 0) return '';
  const days = Math.floor(num / 24);
  const remHours = num % 24;
  const h = translate('settings.unit_hours_short') || 'h';
  const d = translate('settings.unit_days_short') || 'd';
  if (days === 0) return `${num} ${h}`;
  if (remHours === 0) return `${num} ${h} (${days} ${d})`;
  return `${num} ${h} (${days} ${d} ${remHours} ${h})`;
}

/** "5120 MB (~5 GB)": a quota in megabytes, with gigabytes from 1024 up, or '' for no valid value. */
export function formatQuotaMb(mb: string | number | undefined, translate: Translate): string {
  if (mb === undefined || mb === '') return '';
  const num = Number(mb);
  if (!Number.isFinite(num) || num <= 0) return '';
  const mbUnit = translate('settings.unit_mb') || 'MB';
  const gbUnit = translate('settings.unit_gb') || 'GB';
  if (num >= 1024) {
    const gb = (num / 1024).toFixed(1).replace(/\.0$/, '');
    return `${num} ${mbUnit} (~${gb} ${gbUnit})`;
  }
  return `${num} ${mbUnit}`;
}
