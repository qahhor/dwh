// Fails when a form breaks a mechanical rule of the forms standard (docs/guidelines/forms-ux-standard.md).
//
// Rules, checked in the templates of screens and shared blocks (features, layout, shared/entity, shared/ui; the kit
// in shared/ui-kit owns the native controls it wraps):
//
// - raw-control       a native <input>, <select> or <textarea>: use the kit control (smt-input, smt-select, ...).
// - raw-label         a hand-made <label>: name the field with smt-control [smtLabel], which links it.
// - manual-required   a hand-made required mark (class "req", a lone "*"): smt-control draws it with aria-required.
// - manual-error      a hand-made field error (field-error, error-text, upl-field-error): smt-control [smtError]
//                     shows it under the field and links it with aria-describedby / aria-invalid.
// - dialog-footer     a dialog footer row that is not ui-form-actions: one button order and submitting state.
// - disabled-invalid  a button disabled while the form is invalid: keep it enabled and show the errors.
// - module-button-key a module copy of a common button text (upl.common.cancel): use common.save|cancel|...
// - literal-label     a static smtLabel / smtHint / smtTitle text: give it an i18n key.
//
// Today's violations are counted per file in forms-audit-baseline.json; the counts only go down. A count above
// the baseline fails with the lines to fix; a count below it fails until the baseline is lowered with
// `node scripts/forms-audit.mjs --shrink` (which never raises a count). `npm run lint` runs this audit.
import { existsSync } from 'node:fs';
import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const webRoot = process.cwd();
const appRoot = path.join(webRoot, 'src', 'app');
const baselinePath = path.join(webRoot, 'scripts', 'forms-audit-baseline.json');
const SCOPES = ['features', 'layout', 'shared/entity', 'shared/ui'];

/**
 * Controls of their own outside the kit: they wrap the native element and name it themselves, so the two
 * native-element rules do not apply to them. Debt goes to the baseline, never here.
 */
const OWN_CONTROLS = {
  'shared/entity/smt-money-field.component.ts': 'the money control: amount input beside the currency select',
  'shared/ui/ui-markdown-editor.component.html': 'the markdown control: its textarea with a visually hidden label',
  'layout/command-palette/command-palette.component.html': 'the palette combobox: its search input and hidden label',
};
const NATIVE_RULES = new Set(['raw-control', 'raw-label']);

const RULES = {
  'raw-control': 'native control: use the kit control (smt-input, smt-select, smt-textarea, ...)',
  'raw-label': 'hand-made <label>: name the field with smt-control [smtLabel]',
  'manual-required': 'hand-made required mark: smt-control draws "*" and aria-required',
  'manual-error': 'hand-made field error: pass it to smt-control [smtError]',
  'dialog-footer': 'dialog footer without ui-form-actions',
  'disabled-invalid': 'button disabled for invalid values: keep it enabled and show the errors',
  'module-button-key': 'module copy of a common button text: use common.save|cancel|create|close|delete',
  'literal-label': 'static label text: use an i18n key',
};

/** Opening tags with their attributes; quoted values may hold ">". */
const TAG = /<([a-zA-Z][\w-]*)((?:[^<>"']|"[^"]*"|'[^']*')*)>/g;
const NATIVE_CONTROL = new Set(['input', 'select', 'textarea']);
const NON_TEXT_INPUT = /\btype\s*=\s*"(hidden|file)"/;
const MANUAL_ERROR_CLASS = /\bclass\s*=\s*"[^"]*\b(field-error|error-text|upl-field-error)\b[^"]*"/;
const REQ_CLASS = /\bclass\s*=\s*"(?:[^"]*\s)?req(?:\s[^"]*)?"/;
const LONE_STAR = /<span\b[^>]*>\s*\*\s*<\/span>/g;
const FOOTER_ATTRIBUTE = /\s(footer|modal-footer|slot\s*=\s*"footer")(?=[\s>=]|$)/;
const DISABLED_BINDING = /\[disabled\]\s*=\s*"([^"]*)"/;
const INVALID_EXPRESSION = /valid|\.trim\(\)/i;
const KEY_LITERAL = /'([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)'/gi;
const LITERAL_LABEL = /(?:^|\s)(smtLabel|smtHint|smtTitle)\s*=\s*"[^"]*[A-Za-zЀ-ӿ]/;
const COMMON_BUTTONS = ['save', 'cancel', 'create', 'close', 'delete'].map((name) => `common.${name}`);

/**
 * Keys outside `common.` whose Russian text is the text of a common button (`upl.common.cancel`,
 * `iam.org_units.save`): copies that drift apart. Read from the source catalog, so a new copy is found too.
 */
function buttonCopies(catalog) {
  const texts = new Set(COMMON_BUTTONS.map((key) => catalog[key]).filter(Boolean));
  return new Set(Object.keys(catalog).filter((key) => !key.startsWith('common.') && texts.has(catalog[key])));
}

let copies = new Set();

async function files(directory) {
  const found = [];
  let entries = [];
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch {
    return found;
  }
  for (const entry of entries) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...(await files(absolute)));
    else found.push(absolute);
  }
  return found;
}

/** The inline template of a component file and where it starts, or null. */
function inlineTemplate(source) {
  const match = /\btemplate:\s*`/.exec(source);
  if (!match) return null;
  const start = match.index + match[0].length;
  let index = start;
  while (index < source.length && source[index] !== '`') index += source[index] === '\\' ? 2 : 1;
  return { text: source.slice(start, index), start };
}

function lineOf(source, index) {
  return source.slice(0, index).split('\n').length;
}

/** Every violation of one template: rule and line. */
export function templateViolations(text) {
  const found = [];
  const add = (rule, index) => found.push({ rule, index });
  const dialog = /<smt-dialog\b/.test(text);
  for (const match of text.matchAll(TAG)) {
    const [whole, name, attributes] = match;
    const tag = name.toLowerCase();
    if (NATIVE_CONTROL.has(tag) && !(tag === 'input' && NON_TEXT_INPUT.test(attributes)))
      add('raw-control', match.index);
    if (tag === 'label') add('raw-label', match.index);
    if (REQ_CLASS.test(attributes)) add('manual-required', match.index);
    if (MANUAL_ERROR_CLASS.test(attributes)) add('manual-error', match.index);
    const bare = attributes.replace(/"[^"]*"/g, '""');
    if (dialog && tag !== 'ui-form-actions' && FOOTER_ATTRIBUTE.test(bare)) add('dialog-footer', match.index);
    if (tag === 'button' && /\bsmt-button\b/.test(attributes)) {
      const disabled = DISABLED_BINDING.exec(attributes);
      if (disabled && INVALID_EXPRESSION.test(disabled[1])) add('disabled-invalid', match.index);
    }
    if (LITERAL_LABEL.test(attributes)) add('literal-label', match.index);
    void whole;
  }
  // A lone "*" in a span already counted by its class "req" is the same mark.
  for (const match of text.matchAll(LONE_STAR)) if (!REQ_CLASS.test(match[0])) add('manual-required', match.index);
  for (const match of text.matchAll(KEY_LITERAL)) if (copies.has(match[1])) add('module-button-key', match.index);
  return found;
}

async function collect() {
  /** relative file -> rule -> lines */
  const result = new Map();
  for (const scope of SCOPES) {
    for (const file of await files(path.join(appRoot, scope))) {
      const isHtml = file.endsWith('.component.html');
      const isComponent = file.endsWith('.component.ts');
      if (!isHtml && !isComponent) continue;
      const source = await readFile(file, 'utf8');
      const template = isHtml ? { text: source, start: 0 } : inlineTemplate(source);
      if (!template) continue;
      const relative = path.relative(appRoot, file).split(path.sep).join('/');
      for (const { rule, index } of templateViolations(template.text)) {
        if (OWN_CONTROLS[relative] && NATIVE_RULES.has(rule)) continue;
        const byRule = result.get(relative) ?? new Map();
        const lines = byRule.get(rule) ?? [];
        lines.push(lineOf(source, template.start + index));
        byRule.set(rule, lines);
        result.set(relative, byRule);
      }
    }
  }
  return result;
}

function counts(found) {
  const out = {};
  for (const file of [...found.keys()].sort()) {
    out[file] = {};
    for (const rule of Object.keys(RULES)) {
      const lines = found.get(file).get(rule);
      if (lines) out[file][rule] = lines.length;
    }
  }
  return out;
}

async function main() {
  const shrink = process.argv.includes('--shrink');
  const found = await collect();
  const current = counts(found);
  let baseline = null;
  try {
    baseline = JSON.parse(await readFile(baselinePath, 'utf8'));
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
  if (process.argv.includes('--init')) {
    // Only the first count is taken as it is; afterwards the baseline only shrinks.
    if (baseline) throw new Error('scripts/forms-audit-baseline.json exists: use --shrink to lower it');
    await writeFile(baselinePath, JSON.stringify(current, null, 2) + '\n', 'utf8');
    process.stdout.write('Forms audit baseline written.\n');
    return;
  }
  baseline ??= {};
  const grown = [];
  const shrunk = [];
  const next = {};
  for (const file of new Set([...Object.keys(baseline), ...Object.keys(current)])) {
    for (const rule of Object.keys(RULES)) {
      const allowed = baseline[file]?.[rule] ?? 0;
      const now = current[file]?.[rule] ?? 0;
      if (now > allowed) {
        const lines = found.get(file).get(rule).join(', ');
        grown.push(`src/app/${file}:${lines} ${rule} (${now}, allowed ${allowed}): ${RULES[rule]}`);
      } else if (now < allowed) {
        shrunk.push(`${file} ${rule}: ${allowed} -> ${now}`);
      }
      const kept = Math.min(now, allowed);
      if (kept > 0) (next[file] ??= {})[rule] = kept;
    }
  }
  if (shrink) {
    const sorted = Object.fromEntries(
      Object.keys(next)
        .sort()
        .map((file) => [file, next[file]]),
    );
    await writeFile(baselinePath, JSON.stringify(sorted, null, 2) + '\n', 'utf8');
    process.stdout.write(`Forms audit baseline lowered (${shrunk.length} counts).\n`);
    if (grown.length) {
      process.stderr.write(`Forms audit: new violations are never added to the baseline:\n${grown.join('\n')}\n`);
      process.exit(1);
    }
    return;
  }
  const problems = [...grown];
  for (const file of Object.keys(OWN_CONTROLS)) {
    if (!existsSync(path.join(appRoot, file)))
      problems.push(`scripts/forms-audit.mjs: OWN_CONTROLS names ${file}, gone`);
  }
  if (shrunk.length) {
    problems.push(
      `scripts/forms-audit-baseline.json is above today's count; lower it with \`node scripts/forms-audit.mjs --shrink\`:`,
      ...shrunk.map((line) => `  ${line}`),
    );
  }
  if (problems.length) {
    process.stderr.write(`Forms audit failed (docs/guidelines/forms-ux-standard.md):\n${problems.join('\n')}\n`);
    process.exit(1);
  }
  const total = Object.values(current).reduce((sum, rules) => sum + Object.values(rules).reduce((a, b) => a + b, 0), 0);
  process.stdout.write(`Forms audit passed (${total} known violations in the baseline).\n`);
}

/** The rules on known snippets, so a broken pattern fails here instead of passing every template. */
function selfCheck() {
  const rules = (text) => templateViolations(text).map((item) => item.rule);
  const cases = [
    ['<input class="x" [value]="a" />', ['raw-control']],
    ['<input type="file" hidden />', []],
    ['<label for="a">A <span class="req">*</span></label>', ['raw-label', 'manual-required']],
    ['<label>A <span aria-hidden="true">*</span></label>', ['raw-label', 'manual-required']],
    ['<span id="e" class="field-error">x</span>', ['manual-error']],
    ['<smt-dialog><div footer><button smt-button>x</button></div></smt-dialog>', ['dialog-footer']],
    ['<smt-dialog><ui-form-actions footer form="f" /></smt-dialog>', []],
    ['<button smt-button [disabled]="!isFormValid()">x</button>', ['disabled-invalid']],
    ['<button smt-button [disabled]="saving()">x</button>', []],
    ["{{ 'upl.common.cancel' | t }}", ['module-button-key']],
    ['<smt-control smtLabel="Name">', ['literal-label']],
    ['<smt-control [smtLabel]="label()">', []],
  ];
  for (const [text, expected] of cases) {
    const actual = rules(text);
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
      throw new Error(
        `forms-audit self-check: ${text} gave ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`,
      );
    }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const catalogPath = path.resolve(webRoot, '..', 'server', 'src', 'main', 'resources', 'i18n', 'ru.json');
  const catalog = JSON.parse(await readFile(catalogPath, 'utf8'));
  copies = new Set(['upl.common.cancel']);
  selfCheck();
  copies = buttonCopies(catalog);
  await main();
}
