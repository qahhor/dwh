// Plan 10/10, item 3.14 (CODE_STYLE, section 2.1.2): code comments are written in English.
//
// Counts, per TypeScript file under src/, the comment lines that have Cyrillic letters. Comments are found through
// the TypeScript parser, so strings, template literals and regular expressions never count. Files that had Russian
// comments before the rule are listed in comment-language-baseline.txt with their number; the numbers only go down:
// a new file with a Russian comment fails, a listed file whose number grows fails, and a file whose number drops
// fails too until the baseline is lowered with `npm run comments:audit -- --write-baseline`, which lowers numbers
// and drops translated files but never raises a number or adds a file.
import { readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import ts from 'typescript';

const webRoot = process.cwd();
const sourceRoot = path.join(webRoot, 'src');
const baselineFile = path.join(webRoot, 'scripts', 'comment-language-baseline.txt');
const CYRILLIC = /\p{Script=Cyrillic}/u;

async function sources(directory) {
  const found = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...(await sources(absolute)));
    else if (entry.name.endsWith('.ts')) found.push(absolute);
  }
  return found;
}

/** The number of lines of `text` whose comment part has Cyrillic letters. */
export function cyrillicCommentLines(file, text) {
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
  const seen = new Set();
  const lines = new Set();
  const collect = (ranges) => {
    for (const range of ranges ?? []) {
      if (seen.has(range.pos)) continue;
      seen.add(range.pos);
      const comment = text.slice(range.pos, range.end);
      const first = source.getLineAndCharacterOfPosition(range.pos).line;
      comment.split('\n').forEach((line, offset) => {
        if (CYRILLIC.test(line)) lines.add(first + offset);
      });
    }
  };
  const visit = (node) => {
    collect(ts.getLeadingCommentRanges(text, node.getFullStart()));
    collect(ts.getTrailingCommentRanges(text, node.getEnd()));
    for (const child of node.getChildren(source)) visit(child);
  };
  visit(source);
  return lines.size;
}

async function current() {
  const found = new Map();
  for (const file of (await sources(sourceRoot)).sort()) {
    const count = cyrillicCommentLines(file, await readFile(file, 'utf8'));
    if (count > 0) found.set(path.relative(webRoot, file).split(path.sep).join('/'), count);
  }
  return found;
}

async function baseline() {
  const listed = new Map();
  const text = await readFile(baselineFile, 'utf8').catch(() => '');
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const space = line.lastIndexOf(' ');
    listed.set(line.slice(0, space), Number(line.slice(space + 1)));
  }
  return listed;
}

/** The baseline after an update: each listed file at its current number if lower; nothing added or raised. */
export function lowered(found, listed) {
  const kept = new Map();
  for (const [file, count] of listed) {
    const now = found.get(file) ?? 0;
    if (now > 0) kept.set(file, Math.min(now, count));
  }
  return kept;
}

/** The audit checks itself first: literals never count, lines are counted, an update never raises. */
function selfCheck() {
  const ru = String.fromCodePoint(0x416);
  const cases = [
    [`const a = '${ru}'; // plain`, 0],
    [`const a = \`// ${ru} \${b} /* ${ru} */\`;`, 0],
    [`const r = /\\/\\/${ru}/;`, 0],
    [`/**\n * ${ru}\n * english\n * ${ru}\n */\nclass A {}`, 2],
    [`const a = 1; // ${ru}\nconst b = 2; /* ${ru} */ // ${ru}`, 2],
    [`// ${ru} at the end of the file`, 1],
  ];
  const failed = cases.filter(([text, lines]) => cyrillicCommentLines('probe.ts', text) !== lines);
  const update = lowered(
    new Map([
      ['a.ts', 5],
      ['b.ts', 1],
      ['new.ts', 3],
    ]),
    new Map([
      ['a.ts', 3],
      ['b.ts', 4],
      ['gone.ts', 2],
    ]),
  );
  if (
    failed.length ||
    JSON.stringify([...update]) !==
      JSON.stringify([
        ['a.ts', 3],
        ['b.ts', 1],
      ])
  ) {
    process.stderr.write(
      `Comment language audit is broken: ${JSON.stringify(failed)} ${JSON.stringify([...update])}\n`,
    );
    process.exit(2);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(import.meta.filename)) {
  selfCheck();
  const found = await current();
  let listed = await baseline();
  if (process.argv.includes('--write-baseline')) {
    listed = lowered(found, listed);
    const header =
      '# TypeScript files that still have comments in Russian, with the number of their comment lines that have\n' +
      '# Cyrillic letters (plan 10/10, item 3.14, checked by scripts/comment-language-audit.mjs). The numbers only\n' +
      '# go down: translate comments and lower the number (npm run comments:audit -- --write-baseline).\n';
    const body = [...listed].map(([file, count]) => `${file} ${count}`).join('\n');
    await writeFile(baselineFile, header + body + (body ? '\n' : ''));
  }

  const problems = [];
  for (const [file, count] of found) {
    const allowed = listed.get(file);
    if (allowed === undefined) problems.push(`${file}: ${count} comment lines in Russian; write comments in English`);
    else if (count > allowed)
      problems.push(`${file}: ${count} comment lines in Russian, the baseline allows ${allowed}`);
  }
  for (const [file, allowed] of listed) {
    const now = found.get(file) ?? 0;
    if (now < allowed) {
      problems.push(
        `${file}: ${now} comment lines in Russian, fewer than the baseline's ${allowed}; lower it with --write-baseline`,
      );
    }
  }
  if (problems.length) {
    process.stderr.write(`Comment language audit failed (CODE_STYLE, section 2.1.2):\n${problems.join('\n')}\n`);
    process.exit(1);
  }
  process.stdout.write(`Comment language audit passed: ${listed.size} files still listed in the baseline.\n`);
}
