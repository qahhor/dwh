import { inflateRawSync } from 'node:zlib';

/**
 * Reads the text of the cells of an xlsx workbook without a library (Node standard library only): an xlsx is a zip of
 * XML parts; the cells of the first sheet name shared strings or hold inline text and numbers. Enough to check what an
 * export wrote, not a general reader.
 */
export function xlsxRows(file: Buffer): string[][] {
  return xlsxSheet(file, 1);
}

/** The rows of the sheet `index` (1 for the first) of the workbook; none when it has no such sheet. */
export function xlsxSheet(file: Buffer, index: number): string[][] {
  const parts = zipEntries(file);
  const shared = [...(parts.get('xl/sharedStrings.xml') ?? '').matchAll(/<si>([\s\S]*?)<\/si>/gu)].map(([, item]) =>
    [...item.matchAll(/<t[^>]*>([\s\S]*?)<\/t>/gu)].map(([, text]) => unescape(text)).join(''),
  );
  const sheet = parts.get(`xl/worksheets/sheet${index}.xml`) ?? '';
  return [...sheet.matchAll(/<row[^>]*>([\s\S]*?)<\/row>/gu)].map(([, row]) =>
    [...row.matchAll(/<c([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/gu)].map(([, attributes, body = '']) => {
      const value = /<v>([\s\S]*?)<\/v>/u.exec(body)?.[1];
      const inline = /<t[^>]*>([\s\S]*?)<\/t>/u.exec(body)?.[1];
      if (/\bt="s"/u.test(attributes) && value !== undefined) return shared[Number(value)] ?? '';
      if (inline !== undefined) return unescape(inline);
      return value ?? '';
    }),
  );
}

function unescape(text: string): string {
  return text
    .replace(/&#x([0-9a-f]+);/giu, (_, hex: string) => String.fromCodePoint(Number.parseInt(hex, 16)))
    .replace(/&#(\d+);/gu, (_, decimal: string) => String.fromCodePoint(Number(decimal)))
    .replace(/&lt;/gu, '<')
    .replace(/&gt;/gu, '>')
    .replace(/&quot;/gu, '"')
    .replace(/&apos;/gu, "'")
    .replace(/&amp;/gu, '&');
}

/** The XML parts of a zip by name, read from its central directory. */
function zipEntries(file: Buffer): Map<string, string> {
  const end = file.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (end < 0) throw new Error('Not a zip file');
  const count = file.readUInt16LE(end + 10);
  let offset = file.readUInt32LE(end + 16);
  const entries = new Map<string, string>();
  for (let index = 0; index < count; index++) {
    const method = file.readUInt16LE(offset + 10);
    const compressed = file.readUInt32LE(offset + 20);
    const nameLength = file.readUInt16LE(offset + 28);
    const extraLength = file.readUInt16LE(offset + 30);
    const commentLength = file.readUInt16LE(offset + 32);
    const local = file.readUInt32LE(offset + 42);
    const name = file.toString('utf8', offset + 46, offset + 46 + nameLength);
    const dataStart = local + 30 + file.readUInt16LE(local + 26) + file.readUInt16LE(local + 28);
    const data = file.subarray(dataStart, dataStart + compressed);
    entries.set(name, (method === 8 ? inflateRawSync(data) : data).toString('utf8'));
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}
