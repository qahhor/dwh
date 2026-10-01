import { UplPackageItem, UplPackageStatus } from './packages-api';
import { TBadgeVariant } from '@shared/ui-kit/components/badge/badge.component';

/** Load status to dictionary key: the labels live in `ru.json`, not in code. */
export const UPL_PACKAGE_STATUS_KEY: Record<UplPackageStatus, string> = {
  received: 'upl.pkg.status.received',
  verified: 'upl.pkg.status.verified',
  applying: 'upl.pkg.status.applying',
  rejected: 'upl.pkg.status.rejected',
  applied: 'upl.pkg.status.applied',
};

/** Load status to a `ui-badge` variant. */
export const UPL_PACKAGE_STATUS_VARIANT: Record<UplPackageStatus, TBadgeVariant> = {
  received: 'gray',
  verified: 'success',
  applying: 'warning',
  rejected: 'error',
  applied: 'blue',
};

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** `2026-01-31` becomes `31.01.2026`; a string of another form is returned as is, so server data is not hidden. */
export function formatUplDate(isoDate: string): string {
  const parts = ISO_DATE.exec(isoDate ?? '');
  return parts ? `${parts[3]}.${parts[2]}.${parts[1]}` : isoDate;
}

export function formatUplPeriod(from: string, to: string): string {
  return `${formatUplDate(from)}–${formatUplDate(to)}`;
}

/** A server timestamp in the browser's time zone: `dd.mm.yyyy hh:mm`; an unreadable date is shown as it came. */
export function formatUplDateTime(isoInstant: string): string {
  const moment = new Date(isoInstant);
  if (Number.isNaN(moment.getTime())) return isoInstant;
  const day = `${pad(moment.getDate())}.${pad(moment.getMonth() + 1)}.${moment.getFullYear()}`;
  return `${day} ${pad(moment.getHours())}:${pad(moment.getMinutes())}`;
}

/** The load rows "total / accepted / with errors"; a dash until the file is checked. */
export function uplPackageRowsText(item: UplPackageItem): string {
  const counted = item.status === 'verified' || item.status === 'applied';
  if (!counted || item.rowsTotal === null || item.rowsAccepted === null || item.rowsRejected === null) {
    return '—';
  }
  return `${item.rowsTotal} / ${item.rowsAccepted} / ${item.rowsRejected}`;
}
