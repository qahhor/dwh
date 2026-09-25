// Fails when a rule that sets both a background and a text colour resolves,
// in either theme, below the WCAG 2.1 AA contrast minimum.
//
// Pairs are discovered, not listed: any rule declaring `background[-color]`
// and `color` together is checked, so a new component is covered the day it
// is written. Values that cannot be resolved to an opaque colour (gradients,
// `transparent`, alpha, `currentColor`, inherited backgrounds) are skipped —
// their effective contrast depends on what renders behind them.
//
// The vendored UI kit styles with Tailwind utilities, not CSS rules, so the
// same guarantees are checked through the `@theme` bridge in tailwind.css:
// every colour utility the kit uses must map to a token that both themes
// define, and a background and a text utility used together must meet AA.
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const webRoot = process.cwd();
const srcRoot = path.join(webRoot, 'src');
const tokenFile = path.join(srcRoot, 'styles.css');
const bridgeFile = path.join(srcRoot, 'tailwind.css');

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
  ['src/app/features/tasks/components/task-dictionaries-modal.component.ts',
   'the default colour of a task type or status the operator creates'],
  ['src/app/shared/ui-kit/components/forms/color-input/color-input.component.ts',
   'the palette a person picks a status or task type colour from; the chosen colour is stored data'],
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
    else if (/\.(s?css|ts)$/.test(entry.name) && !entry.name.endsWith('.spec.ts')) found.push(absolute);
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

// --- Tailwind bridge -------------------------------------------------------

/** Utility prefix to the theme namespace Tailwind resolves it from. */
const UTILITY_NAMESPACE = {
  bg: 'background-color',
  text: 'text-color',
  border: 'border-color',
  ring: 'ring-color',
};
// A colour name is a hue and a scale step, or white/black. Anything else
// (`text-sm`, `border-2`, `bg-transparent`) is not a palette colour.
const COLOUR_NAME = '(?:white|black|[a-z]+(?:-[a-z]+)*-(?:25|50|[1-9]00|950))';
// Every Tailwind utility that takes a palette colour, so a role the bridge
// does not map yet (`divide-`, `outline-`, ...) is reported rather than missed.
const COLOUR_UTILITY = new RegExp(
  `(?<![\\w-])((?:[a-z-]+:)*)(bg|text|border(?:-[trblxyse])?|ring(?:-offset)?|divide|outline|fill|stroke|placeholder|from|via|to|shadow|accent|caret|decoration)-(${COLOUR_NAME})(/\\d+)?(?![\\w-])`,
  'g',
);

// An arbitrary value (`text-[#9ca3af]`, `bg-(--x,#fff)`, `hover:bg-[rgba(...)]`)
// names its colour inline, so it bypasses the bridge and ignores the theme.
const ARBITRARY_COLOUR = /(?<![\w-])(?:[^\s'"`]*:)?(?:bg|text|border(?:-[trblxyse])?|ring(?:-offset)?|divide|outline|fill|stroke|placeholder|from|via|to|accent|caret|decoration)-[\[(][^\])\s]*(?:#[0-9a-f]{3,8}|rgba?\(|hsla?\(|oklch\(|--)[^\])\s]*[\])]/gi;
// A colour literal anywhere else in a kit template, such as a style binding.
const TEMPLATE_LITERAL = /(?<![\w&])#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3})\b|\b(?:rgba?|hsla?)\(\s*\d[^)]*\)/gi;

async function readBridge() {
  const css = withoutComments(await readFile(bridgeFile, 'utf8'));
  const theme = /@theme(\s+inline)?\s*\{([^}]*)\}/.exec(css);
  if (!theme) throw new Error(`No @theme block found in ${path.relative(webRoot, bridgeFile)}`);
  const mapping = new Map();
  for (const [name, value] of declarations(theme[2])) {
    const match = /^--(background-color|text-color|border-color|ring-color)-(.+)$/.exec(name);
    if (match) mapping.set(`${match[1]}:${match[2]}`, value);
  }
  const sources = [...css.matchAll(/@source\s+['"]([^'"]+)['"]/g)]
    .map(match => path.join(srcRoot, match[1].split('*')[0]));
  return { inline: Boolean(theme[1]), mapping, sources };
}

async function templateFiles(directory) {
  const found = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...await templateFiles(absolute));
    else if (/\.(html|ts)$/.test(entry.name) && !entry.name.endsWith('.spec.ts')) found.push(absolute);
  }
  return found;
}

const bridge = await readBridge();
const bridgeProblems = [];
const bridgeFailures = [];
let bridgeChecked = 0;

if (!bridge.inline) {
  bridgeProblems.push(
    `${path.relative(webRoot, bridgeFile)}: the bridge must be \`@theme inline\`. A plain \`@theme\` ` +
    'declares its variables in `@layer theme`, where an unlayered token of the same name ' +
    '(--color-white) overrides it and the utility stops following the theme.'
  );
}
if (!bridge.sources.length) {
  bridgeProblems.push(`${path.relative(webRoot, bridgeFile)}: no @source, so no vendored template is checked`);
}

for (const [key, value] of bridge.mapping) {
  const colour = THEMES.map(theme => resolveColour(value, tokens[theme]));
  if (colour.some(hex => !hex)) {
    bridgeProblems.push(`${path.relative(webRoot, bridgeFile)} --${key.replace(':', '-')}: ${value} does not resolve to a colour in both themes`);
  }
}

for (const root of bridge.sources) {
  for (const file of await templateFiles(root)) {
    const relative = path.relative(webRoot, file);
    // A file whose colours are data (a palette to pick from) is exempt here too.
    if (LITERAL_ALLOWED.has(relative.split(path.sep).join('/'))) continue;
    const lines = (await readFile(file, 'utf8')).split('\n');
    lines.forEach((text, index) => {
      const arbitrary = [...text.matchAll(ARBITRARY_COLOUR)].map(match => match[0]);
      for (const utility of arbitrary) {
        bridgeProblems.push(`${relative}:${index + 1} ${utility}: an arbitrary colour bypasses the bridge and ignores the theme; use a bridged utility`);
      }
      const rest = arbitrary.reduce((line, utility) => line.replace(utility, ''), text);
      for (const literal of rest.matchAll(TEMPLATE_LITERAL)) {
        bridgeProblems.push(`${relative}:${index + 1} ${literal[0]}: a colour literal in a kit template ignores the theme; name a token from styles.css`);
      }

      const used = [];
      for (const [, variants, prefix, name, alpha] of text.matchAll(COLOUR_UTILITY)) {
        const utility = `${variants}${prefix}-${name}${alpha ?? ''}`;
        const namespace = UTILITY_NAMESPACE[prefix];
        if (!namespace) {
          bridgeProblems.push(`${relative}:${index + 1} ${utility}: the bridge maps no \`${prefix}-\` colours, so this renders uncoloured`);
          continue;
        }
        const value = bridge.mapping.get(`${namespace}:${name}`);
        if (!value) {
          bridgeProblems.push(`${relative}:${index + 1} ${utility}: --${namespace}-${name} is not in the bridge, so this renders uncoloured`);
          continue;
        }
        used.push({ utility, variants, prefix, value, alpha });
      }

      // Opacity makes the result depend on what renders behind it, the same
      // reason the rule audit skips alpha.
      if (/(?<![\w-])opacity-(?!100\b)\d+/.test(text)) return;
      const opaque = used.filter(entry => !entry.alpha);
      for (const background of opaque.filter(entry => entry.prefix === 'bg')) {
        // A `hover:` background is read against the `hover:` text if there is
        // one, and against the resting text otherwise.
        const texts = opaque.filter(entry => entry.prefix === 'text' && entry.variants === background.variants);
        const inks = texts.length ? texts : opaque.filter(entry => entry.prefix === 'text' && entry.variants === '');
        for (const ink of inks) {
          for (const theme of THEMES) {
            const bg = resolveColour(background.value, tokens[theme]);
            const fg = resolveColour(ink.value, tokens[theme]);
            if (!bg || !fg) continue;
            bridgeChecked += 1;
            const ratio = contrast(fg, bg);
            if (ratio < AA_NORMAL) {
              bridgeFailures.push(
                `${relative}:${index + 1} [${theme}] ${ink.utility} on ${background.utility} = ` +
                `${fg} on ${bg} = ${ratio.toFixed(2)}:1, below ${AA_NORMAL.toFixed(1)}:1`
              );
            }
          }
        }
      }
    });
  }
}

if (!bridgeChecked && !bridgeProblems.length) {
  bridgeProblems.push('The bridge audit found no background and text utilities used together; the scan is broken.');
}

if (bridgeProblems.length) {
  process.stderr.write(
    `Tailwind bridge problems:\n${[...new Set(bridgeProblems)].join('\n')}\n\n` +
    'Map the colour in the @theme block of tailwind.css to a token from styles.css, ' +
    'under the namespace of the utility that uses it.\n\n'
  );
}

if (bridgeFailures.length) {
  process.stderr.write(
    `Tailwind colour pairs below WCAG AA contrast:\n${[...new Set(bridgeFailures)].join('\n')}\n\n` +
    'Change the mapping in tailwind.css, not the vendored template.\n\n'
  );
}

if (failures.length || undefinedTokens.length || literals.length || bridgeProblems.length || bridgeFailures.length) {
  process.exit(1);
}

process.stdout.write(
  `Design token audit passed: ${checked} colour pairs at or above WCAG AA in ` +
  `light and dark themes, every colour property naming a token outside the ` +
  `${LITERAL_ALLOWED.size} files where the value is data; ${bridge.mapping.size} bridged ` +
  `Tailwind colours resolving in both themes, ${bridgeChecked} utility pairs at or above AA.\n`
);
