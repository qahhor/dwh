import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { CONVENTION, keyProblems, selfCheck, vocabularies } from './i18n-key-rules.mjs';
import { leftovers, loadMapping } from './i18n-rename-keys.mjs';

/**
 * Keys built from a code at run time (`upl.err.` + error code, `audit.event.` + I/U/D, `nav.` +
 * menu item code): the code keeps its own spelling, the prefix names module and screen.
 */
const CODE_KEY_PREFIXES = ['upl.err.', 'audit.event.', 'projects.state.', 'iam.users.state.', 'nav.'];

const webRoot = process.cwd();
const appRoot = path.join(webRoot, 'src', 'app');
const catalogRoot = path.resolve(webRoot, '..', 'server', 'src', 'main', 'resources', 'i18n');
const supported = ['ru', 'uz', 'en'];
const cyrillic = /[А-Яа-яЁё]/;

async function filesUnder(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) result.push(...(await filesUnder(absolute)));
    // Every source file, not only components: services raise toasts and
    // errors too, and external templates hold keys a templateUrl screen uses.
    else if ((entry.name.endsWith('.ts') && !entry.name.endsWith('.spec.ts')) || entry.name.endsWith('.component.html'))
      result.push(absolute);
  }
  return result;
}

function withoutComments(source) {
  return source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\r\n]*/g, '');
}

const catalogs = {};
for (const code of supported) {
  catalogs[code] = JSON.parse(await readFile(path.join(catalogRoot, `${code}.json`), 'utf8'));
}
const russianKeys = new Set(Object.keys(catalogs.ru));
const nonTranslationIdentifiers = new Set([
  'analytics.dashboard',
  'iam.users',
  'md.custom_fields',
  'md.roles',
  'md.settings',
  'md.users',
  'mf.files',
  'notify.inbox',
  'role.pcode',
  's.pcode',
  'tasks.items',
  'tasks.projects',
  'user.theme',
]);
/**
 * Files where Cyrillic is data rather than UI copy, each with the reason.
 * Everything else must reach the user through a catalog key.
 */
const CYRILLIC_ALLOWED = new Map([
  ['src/app/core/i18n/packaged-russian.ts', 'the generated Russian catalog itself'],
  [
    'src/app/core/services/i18n.service.ts',
    'language endonyms, and the offline dictionary used when no catalog can be fetched',
  ],
]);
const usedKeys = new Set();
const rawCopy = [];

for (const file of await filesUnder(appRoot)) {
  const source = withoutComments(await readFile(file, 'utf8'));
  for (const match of source.matchAll(/['"]([a-z][a-z0-9_.-]+)['"]\s*\|\s*t\b/g)) usedKeys.add(match[1]);
  for (const match of source.matchAll(/\.translate\(\s*['"]([a-z][a-z0-9_.-]+)['"]/g)) usedKeys.add(match[1]);
  // In an .html template a quoted dotted string on the same line is usually a
  // binding expression (`[checked]="field.prefix"`), not a key, so only the
  // exact forms above apply there.
  const lines = file.endsWith('.html') ? [] : source.split(/\r?\n/);
  for (const line of lines.filter((candidate) => candidate.includes('| t'))) {
    for (const match of line.matchAll(/['"]([a-z][a-z0-9_-]*(?:\.[a-z0-9_-]+)+)['"]/g)) {
      usedKeys.add(match[1]);
    }
  }
  // The allow-list is written with '/', which path.relative only returns on POSIX.
  const relative = path.relative(webRoot, file).split(path.sep).join('/');
  source.split(/\r?\n/).forEach((line, index) => {
    if (cyrillic.test(line) && !CYRILLIC_ALLOWED.has(relative))
      rawCopy.push(`${relative}:${index + 1}: ${line.trim()}`);
  });
}

/**
 * Plural forms (I18nService.translate): a key used with a numeric `count` resolves to `<key>.<category>` by the
 * language's plural rules, so a group holds one, few, many and other in every catalog (ru needs all four; uz and en
 * repeat a text where their rules do not tell the forms apart) and each form shows the number.
 */
const PLURAL_CATEGORIES = ['one', 'few', 'many', 'other'];
const pluralGroups = new Set(
  Object.keys(catalogs.ru)
    .filter((key) => key.endsWith('.one'))
    .map((key) => key.slice(0, -'.one'.length)),
);
const pluralProblems = [...pluralGroups].flatMap((group) => [
  ...(russianKeys.has(group) ? [`ru:${group}: a plural group has no text of its own`] : []),
  ...supported.flatMap((code) =>
    PLURAL_CATEGORIES.flatMap((category) => {
      const value = catalogs[code][`${group}.${category}`];
      if (value === undefined) return [`${code}:${group}.${category}: missing`];
      return value.includes('{count}') ? [] : [`${code}:${group}.${category}: shows no {count}`];
    }),
  ),
]);
/**
 * A number glued to a Russian noun ("{count} форм") agrees only with some numbers: such a text is a plural group or
 * puts the number after a label ("Найдено форм: {count}"). Prepositions, abbreviations with a dot and upper-case units
 * do not agree with the number.
 */
const NUMBER_BEFORE_WORD =
  /\{(count|n|m|max|min|total|shown|found|returned|translated|active|done|days|hours|minutes|seconds|limit|fileRows|rawRows|megabytes|periods|succeeded|failed|created|updated|added|changed|removed|accepted|rejected)\}\s+([А-Яа-яЁё]+)(\.?)/g;
// Prepositions and conjunctions, then unit abbreviations written without a dot.
const NEUTRAL_WORDS = new Set(['из', 'до', 'за', 'к', 'на', 'и', 'или', 'по', 'с', 'ч', 'мин', 'д', 'дн']);
const NUMBER_WORD_ALLOWED = new Map([
  ['projects.closed_ratio', 'a ratio "3 / 4 закрыто": the impersonal predicate fits any pair of numbers'],
]);
const unagreedNumbers = Object.entries(catalogs.ru).flatMap(([key, value]) => {
  const group = key.slice(0, key.lastIndexOf('.'));
  if (pluralGroups.has(group) || NUMBER_WORD_ALLOWED.has(key)) return [];
  const words = [...value.matchAll(NUMBER_BEFORE_WORD)]
    .filter(([, , word, dot]) => !dot && !NEUTRAL_WORDS.has(word.toLowerCase()) && word !== word.toUpperCase())
    .map(([match]) => match);
  return words.length ? [`${key}: ${words.join(', ')}`] : [];
});

const missing = [...usedKeys]
  .filter((key) => !nonTranslationIdentifiers.has(key) && !russianKeys.has(key) && !pluralGroups.has(key))
  .sort();
const unknownByLanguage = supported.slice(1).flatMap((code) =>
  Object.keys(catalogs[code])
    .filter((key) => !russianKeys.has(key))
    .map((key) => `${code}:${key}`),
);
/**
 * References of the project's own working documents in a text the user reads (ADR-0031): an ADR, an FR/NFR id, a
 * plan or roadmap item, an invariant or work-item id ("I-P4"), a migration name. They mean nothing to the user and
 * belong in code comments; the catalog says what happens instead.
 */
const INTERNAL_REFERENCES = [
  /\b(?:ADR|N?FR|I|P|W|M)-[A-Z]{0,8}-?\d+(?:\.\d+)*\b/,
  /\bplan 10\/10\b|план 10\/10/i,
  /\b(?:roadmap|plan) item\b|пункт плана/i,
  /инвариант|invariant/i,
  /(?:^|\s)вариант[уа]? [A-ZА-Я](?:$|[\s.,)])/,
  /\bV\d{2,3}__/,
];
const internalReferences = supported.flatMap((code) =>
  Object.entries(catalogs[code])
    .filter(([, value]) => typeof value === 'string' && INTERNAL_REFERENCES.some((pattern) => pattern.test(value)))
    .map(([key, value]) => `${code}:${key}: ${value}`),
);
const invalidValues = supported.flatMap((code) =>
  Object.entries(catalogs[code])
    .filter(([key, value]) => !key.trim() || typeof value !== 'string' || !value.trim() || value.length > 4000)
    .map(([key]) => `${code}:${key}`),
);

// Key names (plan 10/10, item 4.5; ADR-0031): no transliterated, hash-suffixed or truncated key;
// a new key follows <module>.<screen>.<element>; a renamed key does not come back.
const vocab = vocabularies(catalogs.ru, catalogs.en);
const selfCheckFailures = selfCheck(vocab);
const badKeyNames = Object.keys(catalogs.ru).flatMap((key) => {
  const problems = keyProblems(key, catalogs.ru[key], catalogs.en[key], vocab).filter(
    (problem) => problem !== 'format',
  );
  return problems.length ? [`${key}: ${problems.join(', ')}`] : [];
});
const baselineText = await readFile(path.join(webRoot, 'scripts', 'i18n-key-baseline.txt'), 'utf8');
const baseline = new Set(
  baselineText
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#')),
);
const followsConvention = (key) =>
  CONVENTION.test(key) ||
  CODE_KEY_PREFIXES.some((prefix) => key.startsWith(prefix) && /^[A-Za-z0-9_.]+$/.test(key.slice(prefix.length)));
const offConvention = Object.keys(catalogs.ru)
  .filter((key) => !followsConvention(key) && !baseline.has(key))
  .sort();
const staleBaseline = [...baseline].filter((key) => !russianKeys.has(key) || followsConvention(key)).sort();
const renamedLeft = await leftovers(await loadMapping());

if (
  selfCheckFailures.length ||
  badKeyNames.length ||
  offConvention.length ||
  staleBaseline.length ||
  renamedLeft.length
) {
  if (selfCheckFailures.length)
    process.stderr.write(`Key-name checks misjudge known samples:\n${selfCheckFailures.join('\n')}\n`);
  if (badKeyNames.length)
    process.stderr.write(`Transliterated, hash-suffixed or truncated keys (ADR-0031):\n${badKeyNames.join('\n')}\n`);
  if (offConvention.length)
    process.stderr.write(
      `Keys outside <module>.<screen>.<element> in English snake_case (ADR-0031):\n${offConvention.join('\n')}\n`,
    );
  if (staleBaseline.length)
    process.stderr.write(
      `Remove from scripts/i18n-key-baseline.txt (gone or conforming now):\n${staleBaseline.join('\n')}\n`,
    );
  if (renamedLeft.length)
    process.stderr.write(`Renamed keys used again (scripts/i18n-key-renames.json):\n${renamedLeft.join('\n')}\n`);
  process.exit(1);
}

if (
  rawCopy.length ||
  missing.length ||
  unknownByLanguage.length ||
  invalidValues.length ||
  internalReferences.length ||
  pluralProblems.length ||
  unagreedNumbers.length
) {
  if (rawCopy.length) process.stderr.write(`Unlocalized Cyrillic UI copy:\n${rawCopy.join('\n')}\n`);
  if (missing.length) process.stderr.write(`Translation keys missing from ru.json:\n${missing.join('\n')}\n`);
  if (unknownByLanguage.length)
    process.stderr.write(`Non-Russian catalog keys absent from ru.json:\n${unknownByLanguage.join('\n')}\n`);
  if (invalidValues.length) process.stderr.write(`Invalid translation values:\n${invalidValues.join('\n')}\n`);
  if (pluralProblems.length) process.stderr.write(`Incomplete plural groups:\n${pluralProblems.join('\n')}\n`);
  if (unagreedNumbers.length)
    process.stderr.write(
      `A number next to a Russian noun that agrees with some numbers only: use a plural group or a label:\n` +
        `${unagreedNumbers.join('\n')}\n`,
    );
  if (internalReferences.length)
    process.stderr.write(
      `Internal references (ADR, FR, plan items, work-item ids) in user-facing texts:\n${internalReferences.join('\n')}\n`,
    );
  process.exit(1);
}

process.stdout.write(
  `Localization audit passed: ${usedKeys.size} referenced keys, ${russianKeys.size} Russian catalog keys; ` +
    `no transliterated, hash-suffixed or truncated key, ${baseline.size} older keys outside the convention; ` +
    `${pluralGroups.size} complete plural groups, no number glued to a noun.\n`,
);
