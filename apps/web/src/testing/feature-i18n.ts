import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';

/**
 * The i18n contract of one feature (roadmap item 30, an idea from kernel's
 * `*.i18n.spec.ts`): a spec next to the feature names the key namespaces it
 * owns and the keys it builds at run time, and fails on
 *   - a key the feature's files use that ru.json lacks;
 *   - an owned key missing from en.json, where the feature ships English;
 *   - a declared run-time key missing from the catalogs;
 *   - an owned key nothing uses — neither the app's code nor the declared list.
 * `npm run i18n:audit` checks the whole app for the first case only; this
 * sees what it cannot: keys built from data, the English catalog, dead copy.
 */
export interface FeatureI18n {
  /** The feature's folder, relative to apps/web, e.g. `src/app/features/exports`. */
  dir: string;
  /** Key prefixes the feature owns, e.g. `exports.`. */
  owns: readonly string[];
  /** Owned keys must be in en.json too (ui, auth, exports, nav, ... — not upl, which is Russian only). */
  english: boolean;
  /** Keys composed at run time (`'x.status.' + status`), spelled out from the values they come from. */
  dynamic?: readonly string[];
  /** Catalogs to check instead of the server's; only this helper's own spec needs them. */
  catalogs?: { ru: Record<string, string>; en: Record<string, string> };
}

const WEB_ROOT = process.cwd();
const APP_ROOT = path.join(WEB_ROOT, 'src', 'app');
const CATALOG_ROOT = path.resolve(WEB_ROOT, '..', 'server', 'src', 'main', 'resources', 'i18n');
/** The generated catalog holds every key; it proves nothing about use. */
const NOT_A_USE = /packaged-russian\.ts$/;

let cache: { ru: Record<string, string>; en: Record<string, string>; sources: Map<string, string> } | null = null;

function load() {
  if (cache) return cache;
  const catalog = (code: string) => JSON.parse(readFileSync(path.join(CATALOG_ROOT, `${code}.json`), 'utf8')) as Record<string, string>;
  const sources = new Map<string, string>();
  for (const file of sourceFiles(APP_ROOT)) {
    if (!NOT_A_USE.test(file)) sources.set(file, withoutComments(readFileSync(file, 'utf8')));
  }
  cache = { ru: catalog('ru'), en: catalog('en'), sources };
  return cache;
}

function sourceFiles(directory: string): string[] {
  return readdirSync(directory, { recursive: true, encoding: 'utf8' })
    .filter(name => (name.endsWith('.ts') && !name.endsWith('.spec.ts')) || name.endsWith('.html'))
    .map(name => path.join(directory, name));
}

function withoutComments(source: string): string {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:'"`])\/\/[^\r\n]*/g, '$1');
}

/** Keys a file uses the ways the app spells them: `'key' | t`, `translate('key')`, `t('key')`. */
function usedKeys(source: string): string[] {
  const keys: string[] = [];
  for (const match of source.matchAll(/['"]([a-z][a-z0-9_]*(?:\.[A-Za-z0-9_-]+)+)['"]\s*\|\s*t\b/g)) keys.push(match[1]);
  for (const match of source.matchAll(/\b(?:translate|t)\(\s*['"]([a-z][a-z0-9_]*(?:\.[A-Za-z0-9_-]+)+)['"]/g)) keys.push(match[1]);
  return keys;
}

function mentioned(key: string, sources: Iterable<string>): boolean {
  for (const source of sources) {
    if (source.includes(`'${key}'`) || source.includes(`"${key}"`) || source.includes(`\`${key}\``)) return true;
  }
  return false;
}

/** Everything wrong with the feature's keys; empty when the contract holds. */
export function featureI18nProblems(feature: FeatureI18n): string[] {
  const loaded = load();
  const { ru, en } = feature.catalogs ?? loaded;
  const { sources } = loaded;
  const folder = path.join(WEB_ROOT, feature.dir) + path.sep;
  const own = (key: string) => feature.owns.some(prefix => key.startsWith(prefix));
  const dynamic = new Set(feature.dynamic ?? []);
  const problems: string[] = [];

  const featureSources = [...sources].filter(([file]) => file.startsWith(folder));
  if (featureSources.length === 0) return [`${feature.dir}: no source files`];
  for (const [file, source] of featureSources) {
    for (const key of usedKeys(source)) {
      if (!(key in ru)) problems.push(`${path.relative(WEB_ROOT, file).split(path.sep).join('/')}: '${key}' is missing from ru.json`);
    }
  }
  for (const key of dynamic) {
    if (!(key in ru)) problems.push(`run-time key '${key}' is missing from ru.json`);
  }
  const everywhere = [...sources.values()];
  for (const key of Object.keys(ru).filter(own).sort()) {
    if (feature.english && !(key in en)) problems.push(`'${key}' is missing from en.json`);
    if (!dynamic.has(key) && !mentioned(key, everywhere)) problems.push(`'${key}' is not used: remove it, or declare it as a run-time key`);
  }
  return [...new Set(problems)];
}

// ---------- keys the server names ----------
//
// Some keys never appear in the web's code because the server names them: a
// list field's label (`QueryField.of("size", "files.razmer", ...)`), a cell of
// the xlsx it builds, an error code the web turns into `upl.err.<code>` or
// `error.<code>`. A feature spec declares those from the server's own sources
// with the helpers below, so a key the server stops using becomes dead here too.

const SERVER_JAVA_ROOTS = [
  path.resolve(WEB_ROOT, '..', 'server', 'src', 'main', 'java'),
  path.resolve(WEB_ROOT, '..', '..', 'libs'),
];
/** Package root of the server's classes, for `serverCodeKeys({ file })`. */
const SERVER_PACKAGE = path.resolve(WEB_ROOT, '..', 'server', 'src', 'main', 'java', 'com', 'greenwhite', 'dwh');

let serverCache: Map<string, string> | null = null;

function serverSources(): Map<string, string> {
  if (serverCache) return serverCache;
  serverCache = new Map();
  for (const root of SERVER_JAVA_ROOTS) {
    for (const name of readdirSync(root, { recursive: true, encoding: 'utf8' })) {
      const file = path.join(root, name);
      if (!name.endsWith('.java') || /[\\/](test|target)[\\/]/.test(name)) continue;
      serverCache.set(file, withoutComments(readFileSync(file, 'utf8')));
    }
  }
  return serverCache;
}

function catalogKeys(prefix: string): string[] {
  return Object.keys(load().ru).filter(key => key.startsWith(prefix));
}

/** Catalog keys under `prefix` that the server's code names in full, e.g. `"upl.template.title"`. */
export function serverLiteralKeys(prefix: string): string[] {
  const sources = [...serverSources().values()];
  return catalogKeys(prefix).filter(key => sources.some(source => source.includes(`"${key}"`)));
}

/**
 * Catalog keys `prefix + suffix` whose code the server uses: as a string literal
 * (`"UPL_SHEET_NAME_REQUIRED"`), an enum constant (`ErrorCode.I18N_LANGUAGE_INVALID`)
 * or a validation annotation (`@NotBlank`). `toCode` maps the key's suffix to that
 * code (`error.i18n_language_invalid` → `I18N_LANGUAGE_INVALID`); `file` limits the
 * search to one class under com/greenwhite/dwh, for codes as short as a field name.
 */
export function serverCodeKeys(prefix: string, options: { toCode?: (suffix: string) => string; file?: string } = {}): string[] {
  const toCode = options.toCode ?? (suffix => suffix);
  const sources = options.file
    ? [withoutComments(readFileSync(path.join(SERVER_PACKAGE, options.file), 'utf8'))]
    : [...serverSources().values()];
  return catalogKeys(prefix).filter(key => {
    const code = toCode(key.slice(prefix.length));
    const word = new RegExp(`(?<![\\w])@?${code.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![\\w])`);
    return sources.some(source => source.includes(`"${code}"`) || (!options.file && word.test(source)));
  });
}
