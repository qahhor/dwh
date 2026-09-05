function pad(value: number): string {
  return value.toString().padStart(2, '0');
}

export function toLocalDateTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const instant = new Date(iso);
  if (Number.isNaN(instant.getTime())) return '';
  return `${instant.getFullYear()}-${pad(instant.getMonth() + 1)}-${pad(instant.getDate())}`
    + `T${pad(instant.getHours())}:${pad(instant.getMinutes())}`;
}

export function toTaskInstant(value: string, original?: string | null): string | null {
  if (!value) return null;
  if (original && toLocalDateTime(original) === value) return original;
  const localDateTime = new Date(value);
  return Number.isNaN(localDateTime.getTime()) ? null : localDateTime.toISOString();
}
