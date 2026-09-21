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
    .map(match => [match[1].trim().toLowerCase(), match[2].trim()]);
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

/** Resolve a CSS value to an opaque hex, following `var()` chains; null when undecidable. */
function resolveColour(value, tokens, seen = new Set()) {
  const trimmed = value.trim();
  const reference = /^var\(\s*(--[a-z0-9-]+)\s*(?:,[^)]*)?\)$/i.exec(trimmed);
  if (reference) {
    const name = reference[1];
    if (seen.has(name) || !(name in tokens)) return null;
    seen.add(name);
    return resolveColour(tokens[name], tokens, seen);
  }
  return expandHex(trimmed);
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
    line: source.slice(0, match.index).split('\n').length,
  }));
}

function largeText(pairs) {
  const size = parseFloat(pairs.find(([property]) => property === 'font-size')?.[1] ?? '');
  const weightRaw = pairs.find(([property]) => property === 'font-weight')?.[1] ?? '';
  const weight = weightRaw === 'bold' ? 700 : parseInt(weightRaw, 10) || 400;
  if (Number.isNaN(size)) return false;
  return size >= 24 || (size >= 18.66 && weight >= 700);
}

const tokens = await readTokens();
const failures = [];
let checked = 0;

for (const file of await sourceFiles(srcRoot)) {
  const source = await readFile(file, 'utf8');
  for (const { body, line } of ruleBlocks(source)) {
    const pairs = declarations(body);
    const background = pairs.find(([property]) => property === 'background-color' || property === 'background');
    const foreground = pairs.find(([property]) => property === 'color');
    if (!background || !foreground) continue;

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

if (failures.length) {
  process.stderr.write(
    `Colour pairs below WCAG AA contrast:\n${failures.join('\n')}\n\n` +
    'Use the per-theme ink token for the fill (--on-primary, --on-danger, ...) ' +
    'or a lighter scale step for text on a subtle background.\n'
  );
  process.exit(1);
}

process.stdout.write(`Contrast audit passed: ${checked} colour pairs at or above WCAG AA in light and dark themes.\n`);
