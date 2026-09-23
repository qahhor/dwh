// Fails when a template names an element whose role takes no name.
//
// `aria-label` on a plain span, div or paragraph is dropped by screen readers:
// a generic element cannot be named, so the label never reaches the user and
// whatever text the element holds (an icon ligature such as "check_circle")
// is read instead. Give the element a role that takes a name (img, region,
// status, ...), or make the name real text. The browser accessibility gate
// (e2e/tests/a11y) checks the rendered screens it opens; this checks every
// template, including screens it does not reach.
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const appRoot = path.join(process.cwd(), 'src', 'app');
const GENERIC = /<(span|div|p|small|strong|em|b|i|section)\b((?:[^<>"']|"[^"]*"|'[^']*')*)>/g;
const NAMED = /(?:^|\s)(?:\[attr\.aria-label\]|aria-label|\[attr\.aria-labelledby\]|aria-labelledby)\s*=/;
const ROLE = /(?:^|\s)(?:role|\[attr\.role\])\s*=/;
// A <section> with a name is a region landmark by itself.
const IMPLICIT_ROLE = new Set(['section']);

async function templates(directory) {
  const found = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...await templates(absolute));
    else if ((entry.name.endsWith('.ts') && !entry.name.endsWith('.spec.ts')) || entry.name.endsWith('.html')) found.push(absolute);
  }
  return found;
}

const problems = [];
for (const file of await templates(appRoot)) {
  const source = await readFile(file, 'utf8');
  for (const match of source.matchAll(GENERIC)) {
    const [, tag, attributes] = match;
    if (IMPLICIT_ROLE.has(tag) || !NAMED.test(attributes) || ROLE.test(attributes)) continue;
    const line = source.slice(0, match.index).split('\n').length;
    problems.push(`${path.relative(process.cwd(), file)}:${line} <${tag}> is named but has no role, so the name is dropped`);
  }
}

if (problems.length) {
  process.stderr.write(`${problems.join('\n')}\n\nGive the element a role that takes a name, or make the name visible or sr-only text.\n`);
  process.exit(1);
}
process.stdout.write('ARIA name audit passed: every named element in the templates has a role that takes a name.\n');
