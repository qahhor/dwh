// Fails when a rule that sets both a background and a text colour resolves,
// in either theme, below the WCAG 2.1 AA contrast minimum.
//
// Pairs are discovered, not listed: any rule declaring `background[-color]`
// and `color` together is checked, so a new component is covered the day it
// is written. Values that cannot be resolved to an opaque colour (gradients,
// `transparent`, alpha, `currentColor`, inherited backgrounds) are skipped —
// their effective contrast depends on what renders behind them.
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const webRoot = process.cwd();
const srcRoot = path.join(webRoot, 'src');
const tokenFile = path.join(srcRoot, 'styles.css');

const AA_NORMAL = 4.5;
const AA_LARGE = 3.0;
const THEMES = ['light', 'dark'];

const NAMED = { white: '#ffffff', black: '#000000' };

/**
 * Colour properties must name a token, not a literal, so a theme can move
 * them (ADR-0012). These files are the agreed exceptions, each because the
 * value is not styling:
 */
const LITERAL_ALLOWED = new Map([
  ['src/app/features/iam/users/users.models.ts',
   'the avatar palette is a value in the model, picked per user'],
  ['src/app/features/tasks/components/task-dictionaries-modal.component.ts',
   'the default colour of a task type or status the operator creates'],
  ['src/app/features/tasks/services/task-dictionaries.service.ts',
   'the default colour of a task type or status the operator creates'],
  ['src/app/features/notes/notes.component.css',
   'the note card palette the author picks from, each class named for its colour'],
]);

function expandHex(value) {
  const hex = value.trim().toLowerCase();
  if (/^#[0-9a-f]{6}$/.test(hex)) return hex;
  if (/^#[0-9a-f]{3}$/.test(hex)) return `#${[...hex.slice(1)].map(c => c + c).join('')}`;
  return NAMED[hex] ?? null;
}

function channelLuminance(byte) {
  const c = byte / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex) {
  const [r, g, b] = [1, 3, 5].map(i => channelLuminance(parseInt(hex.slice(i, i + 2), 16)));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(foreground, background) {
  const [a, b] = [luminance(foreground), luminance(background)];
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

function declarations(block) {
  return [...block.matchAll(/(?:^|[;{\s])(--[a-z0-9-]+|[a-z-]+)\s*:\s*([^;}]+)/gi)]
    .map(match => [match[1].trim().toLowerCase(), match[2].trim(), match.index ?? 0]);
}

/** Token tables per theme: `:root` declarations, then `[data-theme="dark"]` overrides. */
async function readTokens() {
  const css = await readFile(tokenFile, 'utf8');
  const blockFor = selector => [...css.matchAll(new RegExp(`${selector}\\s*\\{([^{}]*)\\}`, 'g'))]
    .flatMap(match => declarations(match[1]))
    .filter(([name]) => name.startsWith('--'));

  const base = Object.fromEntries(blockFor(':root'));
  const dark = Object.fromEntries(blockFor('\\[data-theme="dark"\\]'));
  if (!Object.keys(base).length || !Object.keys(dark).length) {
    throw new Error(`No design tokens found in ${path.relative(webRoot, tokenFile)}`);
  }
  return { light: base, dark: { ...base, ...dark } };
}

/**
 * Resolve a CSS value to an opaque hex the way a browser would, following
 * `var()` chains and falling back to the literal when the token is undefined.
 * Returns null when the result cannot be decided statically.
 */
function resolveColour(value, tokens, seen = new Set()) {
  // `!important` changes precedence, not the colour.
  const trimmed = value.replace(/!important\s*$/i, '').trim();
  const reference = /^var\(\s*(--[a-z0-9-]+)\s*(?:,\s*([^)]*))?\)$/i.exec(trimmed);
  if (reference) {
    const [, name, fallback] = reference;
    if (seen.has(name)) return null;
    seen.add(name);
    // An undefined token paints its fallback, so that is what must be measured.
    if (!(name in tokens)) return fallback ? resolveColour(fallback, tokens, seen) : null;
    return resolveColour(tokens[name], tokens, seen);
  }
  return expandHex(trimmed);
}

/** Every `--token` a value refers to, so undefined ones can be reported. */
function referencedTokens(value) {
  return [...value.matchAll(/var\(\s*(--[a-z0-9-]+)/gi)].map(match => match[1]);
}

async function sourceFiles(directory) {
  const found = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...await sourceFiles(absolute));
    else if (/\.(css|ts)$/.test(entry.name) && !entry.name.endsWith('.spec.ts')) found.push(absolute);
  }
  return found;
}

function withoutComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, '');
}

/** Innermost brace blocks — a CSS rule body never nests another rule. */
function ruleBlocks(source) {
  return [...withoutComments(source).matchAll(/\{([^{}]*)\}/g)].map(match => ({
    body: match[1],
    // Offset of the body, so a finding can name the declaration's own line.
    offset: (match.index ?? 0) + 1,
  }));
}

function lineAt(source, offset) {
  return source.slice(0, offset).split('\n').length;
}

const COLOUR_PROPERTY = /^(background-color|background|border-color|border-top-color|border-right-color|border-bottom-color|border-left-color|color|outline-color|fill|stroke)$/;

function largeText(pairs) {
  const size = parseFloat(pairs.find(([property]) => property === 'font-size')?.[1] ?? '');
  const weightRaw = pairs.find(([property]) => property === 'font-weight')?.[1] ?? '';
  const weight = weightRaw === 'bold' ? 700 : parseInt(weightRaw, 10) || 400;
  if (Number.isNaN(size)) return false;
  return size >= 24 || (size >= 18.66 && weight >= 700);
}

const tokens = await readTokens();
const failures = [];
const undefinedTokens = [];
const literals = [];
let checked = 0;

for (const file of await sourceFiles(srcRoot)) {
  const source = await readFile(file, 'utf8');
  const relative = path.relative(webRoot, file);

  if (!LITERAL_ALLOWED.has(relative)) {
    for (const { body, offset } of ruleBlocks(source)) {
      for (const [property, value, at] of declarations(body)) {
        if (!COLOUR_PROPERTY.test(property)) continue;
        // A literal inside a var() fallback is reported as an undefined token instead.
        const outside = value.replace(/var\([^)]*\)/g, m => ' '.repeat(m.length));
        for (const literal of outside.matchAll(/#[0-9a-fA-F]{3,8}/g)) {
          literals.push(`${relative}:${lineAt(source, offset + at)} ${property}: ${literal[0]} is a literal; name a token so the theme can move it`);
        }
      }
    }
  }

  for (const { body, offset } of ruleBlocks(source)) {
    const pairs = declarations(body);
    const background = pairs.find(([property]) => property === 'background-color' || property === 'background');
    const foreground = pairs.find(([property]) => property === 'color');
    if (!background || !foreground) continue;

    for (const [property, value, at] of [background, foreground]) {
      for (const name of referencedTokens(value)) {
        if (!(name in tokens.light) && !(name in tokens.dark)) {
          undefinedTokens.push(`${path.relative(webRoot, file)}:${lineAt(source, offset + at)} ${property}: ${name} is not defined, so its fallback paints in both themes`);
        }
      }
    }

    const line = lineAt(source, offset + foreground[2]);

    const threshold = largeText(pairs) ? AA_LARGE : AA_NORMAL;
    for (const theme of THEMES) {
      const table = tokens[theme];
      const bg = resolveColour(background[1], table);
      const fg = resolveColour(foreground[1], table);
      if (!bg || !fg) continue;

      checked += 1;
      const ratio = contrast(fg, bg);
      if (ratio < threshold) {
        failures.push(
          `${path.relative(webRoot, file)}:${line} [${theme}] ${fg} on ${bg} ` +
          `= ${ratio.toFixed(2)}:1, below ${threshold.toFixed(1)}:1 ` +
          `(${foreground[1]} on ${background[1]})`
        );
      }
    }
  }
}

if (!checked) {
  process.stderr.write('Contrast audit found no resolvable colour pairs; the parser is broken.\n');
  process.exit(1);
}

if (undefinedTokens.length) {
  process.stderr.write(
    `Colour properties referencing an undefined design token:\n${[...new Set(undefinedTokens)].join('\n')}\n\n` +
    'Point these at a token defined in styles.css. A `var()` fallback is not a theme: ' +
    'it paints the same colour in light and dark.\n\n'
  );
}

if (failures.length) {
  process.stderr.write(
    `Colour pairs below WCAG AA contrast:\n${failures.join('\n')}\n\n` +
    'Use the per-theme ink token for the fill (--on-primary, --on-danger, ...) ' +
    'or a lighter scale step for text on a subtle background.\n'
  );
}

if (literals.length) {
  process.stderr.write(
    `Colour properties written as a literal instead of a token:\n${literals.join('\n')}\n\n` +
    'If the value is data rather than styling, add the file to LITERAL_ALLOWED ' +
    'in this script with the reason.\n\n'
  );
}

if (failures.length || undefinedTokens.length || literals.length) {
  process.exit(1);
}

process.stdout.write(
  `Design token audit passed: ${checked} colour pairs at or above WCAG AA in ` +
  `light and dark themes, every colour property naming a token outside the ` +
  `${LITERAL_ALLOWED.size} files where the value is data.\n`
);
