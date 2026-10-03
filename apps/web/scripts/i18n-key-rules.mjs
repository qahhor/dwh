// Naming rules for translation keys (plan 10/10, item 4.5; ADR-0031): a key is
// <module>.<screen>.<element> in English snake_case. Shared by the localization audit and the
// key rename script, so both judge a key the same way.

/** Letters of the transliteration the old key generator used (GOST-like, without diacritics). */
const CYRILLIC_TO_LATIN = {
  а: 'a',
  б: 'b',
  в: 'v',
  г: 'g',
  д: 'd',
  е: 'e',
  ё: 'e',
  ж: 'zh',
  з: 'z',
  и: 'i',
  й: 'y',
  к: 'k',
  л: 'l',
  м: 'm',
  н: 'n',
  о: 'o',
  п: 'p',
  р: 'r',
  с: 's',
  т: 't',
  у: 'u',
  ф: 'f',
  х: 'h',
  ц: 'c',
  ч: 'ch',
  ш: 'sh',
  щ: 'sch',
  ъ: '',
  ы: 'y',
  ь: '',
  э: 'e',
  ю: 'yu',
  я: 'ya',
};
const CYRILLIC = /[а-яё]/i;

/** One key segment: lower-case English words joined by single underscores, starting with a letter. */
export const SEGMENT = /^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/;
/** The convention for new keys: at least module, screen and element. */
export const CONVENTION = /^[a-z][a-z0-9]*(?:_[a-z0-9]+)*(?:\.[a-z][a-z0-9]*(?:_[a-z0-9]+)*){2,}$/;
/** A content hash glued to a key to make it unique (`audit.tablica.6f39b76`). */
const HASH_SEGMENT = /^(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{6,}$/;
const HASH_SUFFIX = /_(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{6,}$/;

/**
 * Words that may stand in a key although they also spell a Russian word: proper names, codes
 * and operator names. Keep it short; every entry needs a reason.
 */
export const WORD_ALLOWLIST = new Map([
  ['ne', 'the "not equal" filter operator (ui.filter.op.ne)'],
  ['stat', 'short for statistic (modules.stat.*, nav.settings.stat_*)'],
  ['sha', 'the hash algorithm in a constraint name (error.warehouse.fnd_load_log_ck_file_sha)'],
  ['uz', 'a language code'],
  ['ru', 'a language code'],
  ['en', 'a language code'],
]);

export function transliterate(text) {
  return text
    .toLowerCase()
    .split('')
    .map((char) => CYRILLIC_TO_LATIN[char] ?? char)
    .join('');
}

/** Words of a text as a key generator would slug them. */
export function slugWords(text) {
  return (text ?? '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
    .split(/\s+/)
    .filter(Boolean);
}

/**
 * Vocabularies the checks need: English words of the English catalog, and Latin spellings of the
 * Cyrillic words of the Russian catalog.
 */
export function vocabularies(russian, english) {
  const englishWords = new Set();
  for (const value of Object.values(english)) for (const word of slugWords(value)) englishWords.add(word);
  const translitWords = new Set();
  for (const value of Object.values(russian)) {
    for (const word of value.toLowerCase().split(/[^a-zа-яё0-9]+/)) {
      if (CYRILLIC.test(word)) for (const part of slugWords(transliterate(word))) translitWords.add(part);
    }
  }
  return { englishWords, translitWords };
}

function isEnglish(word, englishWords) {
  return (
    /^\d+$/.test(word) ||
    WORD_ALLOWLIST.has(word) ||
    englishWords.has(word) ||
    englishWords.has(word.replace(/s$/, '')) ||
    englishWords.has(word.replace(/(ed|ing|es)$/, ''))
  );
}

/**
 * Whether `words` start like `target` and then stop inside one of its words. Two-word elements are
 * left alone: `owner_org` and `user_ref` are abbreviations, not a phrase cut at a length limit.
 */
function cutInsideWord(words, target) {
  if (words.length < 3 || words.length > target.length) return false;
  const last = words.length - 1;
  for (let index = 0; index < last; index++) if (words[index] !== target[index]) return false;
  if (target[last] !== words[last]) return target[last].startsWith(words[last]);
  // Cut at a word boundary: only a long element that is a strict beginning of the phrase counts.
  return words.length < target.length && words.join('_').length >= LONG_ELEMENT;
}

/** The old generator cut keys at 48 characters; an element this long is a phrase, not a name. */
const LONG_ELEMENT = 40;

/**
 * Problems of one key: `translit`, `hash`, `truncated`, `format`. `ru` and `en` are the key's
 * values (English may be missing, for Russian-only modules).
 */
export function keyProblems(key, ru, en, vocab) {
  const problems = new Set();
  const segments = key.split('.');
  const translitValue = slugWords(transliterate(ru ?? ''));
  const englishValue = slugWords(en ?? '');
  for (const [index, segment] of segments.entries()) {
    if (HASH_SEGMENT.test(segment) || HASH_SUFFIX.test(segment)) {
      problems.add('hash');
      continue;
    }
    if (segment.endsWith('_') || segment.includes('__')) problems.add('truncated');
    if (index === 0) continue;
    const words = segment.split('_').filter(Boolean);
    const russianWords = words.filter((word) => vocab.translitWords.has(word) && !isEnglish(word, vocab.englishWords));
    if (russianWords.length) problems.add('translit');
    if (cutInsideWord(words, translitValue) || cutInsideWord(words, englishValue)) problems.add('truncated');
  }
  if (!segments.every((segment, index) => (index === 0 ? /^[a-z][a-z0-9_]*$/ : /^[A-Za-z0-9_]+$/).test(segment)))
    problems.add('format');
  return [...problems];
}

/** Known bad and good keys: the checks must keep telling them apart. */
export const SELF_CHECK = [
  { key: 'tasks.soispolniteli', ru: 'Соисполнители', en: 'Co-executors', expect: ['translit'] },
  { key: 'iam.kanaly_svyazi', ru: 'Каналы связи', en: 'Communication channels', expect: ['translit'] },
  { key: 'audit.tablica.6f39b76', ru: 'Таблица:', en: 'Table:', expect: ['hash', 'translit'] },
  { key: 'audit.details.table_label.6f39b76', ru: 'Таблица:', en: 'Table:', expect: ['hash'] },
  {
    key: 'analytics.raspredelenie_aktivnyh_i_vypolnennyh_zadach_po_i',
    ru: 'Распределение активных и выполненных задач по исполнителям',
    en: 'Distribution of active and completed tasks by executor',
    expect: ['translit', 'truncated'],
  },
  {
    key: 'announcements.list.publish_important_messages_to_us',
    ru: 'Публикуйте важные сообщения пользователям',
    en: 'Publish important messages to users',
    expect: ['truncated'],
  },
  { key: 'announcements.list.subtitle_', ru: 'Публикуйте', en: 'Publish', expect: ['truncated'] },
  { key: 'tasks.common.co_executors', ru: 'Соисполнители', en: 'Co-executors', expect: [] },
  { key: 'iam.roles.search', ru: 'Поиск ролей', en: 'Search for roles', expect: [] },
  { key: 'ui.filter.op.ne', ru: 'не равно', en: 'does not equal', expect: [] },
  { key: 'upl.format.encoding.windows_1251', ru: 'windows-1251', en: 'windows-1251', expect: [] },
];

/** Runs the self-check; returns the samples whose verdict changed. */
export function selfCheck(vocab) {
  return SELF_CHECK.filter((sample) => {
    const found = keyProblems(sample.key, sample.ru, sample.en, vocab).sort().join(',');
    return found !== [...sample.expect].sort().join(',');
  }).map((sample) => `${sample.key}: expected [${sample.expect.join(', ')}]`);
}
