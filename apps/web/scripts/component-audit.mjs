// Fails when a component breaks the structure rules of CODE_STYLE (section 6, plan 10/10, items 2.4 and 2.7).
//
// - A component file holds at most 400 lines; an inline template at most 150 lines (a longer one moves to a
//   sibling .html) and inline styles at most 50 lines (longer ones move to a sibling .css).
// - Every component has its own spec beside it (<name>.component.spec.ts).
// - A screen's title is the <h1> of ui-page-header: no other template writes its own <h1>.
// - The element that wraps a screen's ui-page-header adds no padding of its own: the shell's .page-content gives every
//   screen the same gutter, and a second one indents that screen's title and actions against the others.
//
// An exception is named in ALLOWED below with its reason; the list only shrinks. `npm run lint` runs this audit.
import { readdir, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const appRoot = path.join(process.cwd(), 'src', 'app');
const LIMITS = { file: 400, template: 150, styles: 50 };

/** Exceptions by rule: path relative to src/app -> reason. */
const ALLOWED = {
  file: {
    // Vendored kit primitives (ADR-0012): kept close to the upstream source so its fixes can be taken over.
    'shared/ui-kit/components/table/table.component.ts': 'vendored kit table (ADR-0012)',
    'shared/ui-kit/components/forms/select/select.component.ts': 'vendored kit select (ADR-0012)',
    'shared/ui-kit/components/forms/tree-select/tree-select.component.ts': 'vendored kit tree select (ADR-0012)',
  },
  template: {},
  styles: {},
  spec: {},
  padding: {},
  h1: {
    // The heading block itself.
    'shared/ui/ui-page-header.component.ts': 'the page header renders the screen title',
    // The sign-in screen has no application shell and no page header: its card names the product.
    'features/auth/login/components/login-header.component.ts': 'sign-in card outside the application shell',
    // A report embedded in an iframe keeps a one-line toolbar (icon, title, host, status, fullscreen and popup
    // actions) that stays on screen in fullscreen mode; the page header would take the height the frame needs.
    'features/reports/embedded-report.component.ts': 'compact toolbar of a full-height embedded report',
  },
};

async function files(directory) {
  const found = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...(await files(absolute)));
    else found.push(absolute);
  }
  return found;
}

/** The text of a backtick literal that starts right after `offset`, or null. */
function literalAt(source, offset) {
  const start = source.indexOf('`', offset);
  if (start < 0) return null;
  let index = start + 1;
  while (index < source.length && source[index] !== '`') index += source[index] === '\\' ? 2 : 1;
  return { text: source.slice(start + 1, index), start };
}

/** Lines of a literal's content, the lines holding only the opening and closing backtick left out. */
function contentLines(text) {
  return text
    .replace(/^[ \t]*\r?\n/, '')
    .replace(/\r?\n[ \t]*$/, '')
    .split('\n').length;
}

function lineOf(source, index) {
  return source.slice(0, index).split('\n').length;
}

const problems = [];
const used = new Set();
const allowed = (rule, relative) => {
  if (ALLOWED[rule][relative] === undefined) return false;
  used.add(`${rule}:${relative}`);
  return true;
};

const H1 = /<h1[\s>]/g;

/** The classes of the first element of a template, when that element wraps the screen's ui-page-header. */
function pageWrapperClasses(markup) {
  const text = markup.replace(/<!--[\s\S]*?-->/g, '');
  const header = text.indexOf('<ui-page-header');
  if (header < 0) return [];
  const first = /<([a-z][\w-]*)\b([^>]*)>/.exec(text);
  if (!first || first.index >= header || first[1] === 'ui-page-header') return [];
  // A sibling before the header (a loading placeholder) closes before it; only an element still open wraps it.
  const closed = text.indexOf(`</${first[1]}>`, first.index);
  if (closed >= 0 && closed < header && !text.slice(first.index + first[0].length, closed).includes(`<${first[1]}`))
    return [];
  const classes = /\sclass="([^"]+)"/.exec(first[2]);
  return classes ? classes[1].trim().split(/\s+/) : [];
}

/** The declarations of the rules whose selector is exactly `.name`. */
function ruleBodies(css, name) {
  const rule = new RegExp(`(?:^|[}\\s,])\\.${name.replace(/-/g, '\\-')}\\s*\\{([^}]*)\\}`, 'g');
  return [...css.matchAll(rule)].map((match) => match[1]);
}

/** A quoted text bound straight to what the page header shows, not through the catalog. */
const LITERAL_HEADER = /<ui-page-header\b[^>]*?\[(?:title|eyebrow|subtitle|count)\]="('[^']*')"/g;

/** A padding other than zero that moves the header: on every side, at the top or the sides (not room at the bottom). */
const OWN_PADDING =
  /(?:^|[;\s])padding(?:-(?:top|left|right|inline|inline-start|block-start))?\s*:(?!\s*0(?:px)?\s*(?:;|$))/;

/** The styles of a component: its .css file and its inline styles. */
async function componentStyles(componentFile, componentSource) {
  const cssFile = componentFile.replace(/\.ts$/, '.css');
  const inline = /\bstyles:\s*\[?\s*(?=`)/.exec(componentSource);
  return [
    existsSync(cssFile) ? await readFile(cssFile, 'utf8') : '',
    inline ? (literalAt(componentSource, inline.index + inline[0].length)?.text ?? '') : '',
  ].join('\n');
}
const all = await files(appRoot);
for (const file of all) {
  const relative = path.relative(appRoot, file).split(path.sep).join('/');
  const shown = path.relative(process.cwd(), file);
  const isComponent = relative.endsWith('.component.ts');
  const isTemplate = relative.endsWith('.component.html');
  if (!isComponent && !isTemplate) continue;
  const source = await readFile(file, 'utf8');
  const template = /\btemplate:\s*(?=`)/.exec(source);
  const templateLiteral = template ? literalAt(source, template.index + template[0].length) : null;

  // In a component file only its inline template is markup; elsewhere `<h1>` may be a string the code builds.
  const markup = isTemplate ? { text: source, start: -1 } : templateLiteral;
  for (const match of markup ? markup.text.matchAll(H1) : []) {
    const owner = isTemplate ? relative.replace(/\.html$/, '.ts') : relative;
    if (allowed('h1', owner)) continue;
    const line = lineOf(source, markup.start + 1 + match.index);
    problems.push(`${shown}:${line} own <h1>: give the screen its title through ui-page-header`);
  }
  for (const match of markup ? markup.text.matchAll(LITERAL_HEADER) : []) {
    const line = lineOf(source, markup.start + 1 + match.index);
    problems.push(`${shown}:${line} ui-page-header shows the literal ${match[1]}: give it a catalog key ( | t)`);
  }
  const owner = isTemplate ? relative.replace(/\.html$/, '.ts') : relative;
  const wrapper = markup ? pageWrapperClasses(markup.text) : [];
  if (wrapper.length && !allowed('padding', owner)) {
    const ownerFile = path.join(appRoot, owner);
    const css = await componentStyles(ownerFile, isTemplate ? await readFile(ownerFile, 'utf8') : source);
    for (const name of wrapper.filter((candidate) =>
      ruleBodies(css, candidate).some((body) => OWN_PADDING.test(body)),
    )) {
      problems.push(`${shown} .${name} wraps ui-page-header and pads it: the shell's .page-content gives the gutter`);
    }
  }
  if (!isComponent) continue;

  const lines = source.split('\n').length - (source.endsWith('\n') ? 1 : 0);
  if (lines > LIMITS.file && !allowed('file', relative)) {
    problems.push(`${shown} has ${lines} lines (max ${LIMITS.file}): split it into parts`);
  }
  if (templateLiteral) {
    const count = contentLines(templateLiteral.text);
    if (count > LIMITS.template && !allowed('template', relative)) {
      problems.push(
        `${shown}:${lineOf(source, template.index)} inline template has ${count} lines (max ${LIMITS.template}): move it to a .html file`,
      );
    }
  }
  const styles = /\bstyles:\s*\[?\s*(?=`)/.exec(source);
  if (styles) {
    const literal = literalAt(source, styles.index + styles[0].length);
    const count = literal ? contentLines(literal.text) : 0;
    if (count > LIMITS.styles && !allowed('styles', relative)) {
      problems.push(
        `${shown}:${lineOf(source, styles.index)} inline styles have ${count} lines (max ${LIMITS.styles}): move them to a .css file`,
      );
    }
  }
  if (!existsSync(file.replace(/\.ts$/, '.spec.ts')) && !allowed('spec', relative)) {
    problems.push(`${shown} has no spec beside it (${path.basename(file, '.ts')}.spec.ts)`);
  }
}

for (const [rule, entries] of Object.entries(ALLOWED)) {
  for (const relative of Object.keys(entries)) {
    if (!used.has(`${rule}:${relative}`)) {
      problems.push(
        `scripts/component-audit.mjs: the ${rule} exception for ${relative} is no longer needed; remove it`,
      );
    }
  }
}

if (problems.length) {
  process.stderr.write(`Component structure audit failed (CODE_STYLE section 6):\n${problems.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write('Component structure audit passed.\n');
