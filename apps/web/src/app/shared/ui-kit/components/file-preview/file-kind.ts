/* Our code: what kind of file a name and a media type describe, for the file
 * card's icon and for deciding what can be shown in the browser. Replaces
 * two copies of the same rules (the files list and the upload field). */

export type SMTFileKind = 'image' | 'pdf' | 'doc' | 'sheet' | 'archive' | 'other';

const ICONS: Readonly<Record<SMTFileKind, string>> = {
  image: 'image',
  pdf: 'picture_as_pdf',
  doc: 'description',
  sheet: 'table_chart',
  archive: 'folder_zip',
  other: 'attach_file',
};

/** Images a browser draws in an <img>, where no script in the file can run. */
const VIEWABLE_IMAGE = /^image\/(png|jpe?g|gif|webp|avif|bmp|svg\+xml)$/;
const VIEWABLE_IMAGE_NAME = /\.(png|jpe?g|gif|webp|avif|bmp|svg)$/;

export function fileKind(mimeType?: string | null, fileName?: string | null): SMTFileKind {
  const mime = (mimeType ?? '').toLowerCase();
  const name = (fileName ?? '').toLowerCase();
  if (mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|avif|bmp|svg)$/.test(name)) return 'image';
  if (mime === 'application/pdf' || name.endsWith('.pdf')) return 'pdf';
  if (/\.(docx?|odt|rtf|txt|md)$/.test(name)) return 'doc';
  if (/\.(xlsx?|csv|ods)$/.test(name)) return 'sheet';
  if (/\.(zip|tar|gz|rar|7z)$/.test(name)) return 'archive';
  return 'other';
}

export function fileKindIcon(kind: SMTFileKind): string {
  return ICONS[kind];
}

/**
 * Whether the preview can show the file. Only images, drawn in an <img>: a
 * PDF or an office file would have to be embedded inline, which the API
 * refuses on purpose (attachment disposition, frame-ancestors 'none').
 */
export function canPreview(mimeType?: string | null, fileName?: string | null): boolean {
  const mime = (mimeType ?? '').toLowerCase();
  if (mime) return VIEWABLE_IMAGE.test(mime);
  return VIEWABLE_IMAGE_NAME.test((fileName ?? '').toLowerCase());
}

const UNITS = ['byte', 'kilobyte', 'megabyte', 'gigabyte', 'terabyte'] as const;

/** "1,5 МБ" / "1.5 MB": the size in the largest unit below 1024, in the person's language. */
export function formatFileSize(bytes: number | null | undefined, locale: string): string {
  const size = Math.max(0, Number(bytes) || 0);
  const power = size === 0 ? 0 : Math.min(UNITS.length - 1, Math.floor(Math.log(size) / Math.log(1024)));
  const value = size / 1024 ** power;
  return new Intl.NumberFormat(locale, {
    style: 'unit',
    unit: UNITS[power],
    unitDisplay: 'short',
    maximumFractionDigits: power === 0 ? 0 : 1,
  }).format(value);
}
