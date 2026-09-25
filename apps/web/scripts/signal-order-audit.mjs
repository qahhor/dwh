// Fails when an Angular class lists its members out of the agreed order.
//
// Ported from smartup-ui-kit's ESLint rule `order-angular-signals` (MIT) to
// the TypeScript compiler API, since this application has no ESLint (roadmap
// item 30). Members go in this order, and within each group public, protected,
// private:
//   inject → input.required → input → output → model.required → model →
//   viewChild(.required) → viewChildren(.required) → contentChild(.required) →
//   contentChildren(.required) → signal/linkedSignal → computed → effect →
//   other fields → constructor → methods and accessors.
// One deliberate difference: other fields keep their written order whatever
// their visibility, because an initializer may read a field declared before
// it (a public stream over a private subject) and TypeScript forbids the
// reverse. `--fix <file>...` reorders the named files the same stable way,
// comments travelling with their member; run the typecheck after it, which
// catches an initializer that now reads a field declared below it.
//
// Classes that broke the order before the rule existed are listed in
// signal-order-baseline.txt; a class leaves the list once it is put in order,
// and the audit fails on an entry that no longer breaks it, so the list only
// ever shrinks.
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import ts from 'typescript';

const webRoot = process.cwd();
const appRoot = path.join(webRoot, 'src', 'app');
const baselineFile = path.join(webRoot, 'scripts', 'signal-order-baseline.txt');
const ANGULAR = new Set(['Component', 'Directive', 'Injectable', 'Pipe']);

const BASE = {
  inject: 10, 'input.required': 15, input: 20, output: 30, 'model.required': 35, model: 40,
  'viewChild.required': 45, viewChild: 50, 'viewChildren.required': 55, viewChildren: 60,
  'contentChild.required': 65, contentChild: 70, 'contentChildren.required': 75, contentChildren: 80,
  signal: 90, linkedSignal: 90, computed: 100, effect: 110
};
const REQUIRABLE = new Set(['input', 'model', 'viewChild', 'viewChildren', 'contentChild', 'contentChildren']);
const LABEL = { 120: 'field', 130: 'constructor', 140: 'method' };

async function sources(directory) {
  const found = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...await sources(absolute));
    else if (entry.name.endsWith('.ts') && !entry.name.endsWith('.spec.ts') && !entry.name.endsWith('.d.ts')) found.push(absolute);
  }
  return found;
}

/** `input()`, `input<T>()`, `input.required<T>()`, `inject(X)` ... → its kind, or null for an ordinary field. */
function signalKind(initializer) {
  if (!initializer || !ts.isCallExpression(initializer)) return null;
  const callee = initializer.expression;
  if (ts.isIdentifier(callee)) return callee.text in BASE ? callee.text : null;
  if (ts.isPropertyAccessExpression(callee) && callee.name.text === 'required' && ts.isIdentifier(callee.expression)
      && REQUIRABLE.has(callee.expression.text)) {
    return `${callee.expression.text}.required`;
  }
  return null;
}

function visibility(member) {
  const flags = ts.getCombinedModifierFlags(member);
  if (flags & ts.ModifierFlags.Private || (member.name && ts.isPrivateIdentifier(member.name))) return 2;
  if (flags & ts.ModifierFlags.Protected) return 1;
  return 0;
}

/** The member's rank, or null for what the rule does not place (index signatures, static blocks). */
function rank(member) {
  if (ts.isConstructorDeclaration(member)) return { group: 130, order: 130 };
  if (ts.isMethodDeclaration(member) || ts.isGetAccessor(member) || ts.isSetAccessor(member)) {
    return { group: 140, order: 140 + visibility(member) };
  }
  if (!ts.isPropertyDeclaration(member)) return null;
  const kind = signalKind(member.initializer);
  if (kind) return { group: BASE[kind], order: BASE[kind] + visibility(member), kind };
  return { group: 120, order: 120 };
}

function describe(member, placed) {
  const name = member.name ? member.name.getText() : 'constructor';
  return `${placed.kind ?? LABEL[placed.group]} ${name}`;
}

function isAngular(node) {
  return (ts.getDecorators(node) ?? []).some(decorator =>
    ts.isCallExpression(decorator.expression) && ts.isIdentifier(decorator.expression.expression)
    && ANGULAR.has(decorator.expression.expression.text));
}

/** The first member that stands after one that should follow it, per Angular class. */
function violations(file, text) {
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
  const found = [];
  const visit = node => {
    if (ts.isClassDeclaration(node) && node.name && isAngular(node)) {
      let highest = null;
      for (const member of node.members) {
        const placed = rank(member);
        if (!placed) continue;
        if (highest && placed.order < highest.placed.order) {
          const line = source.getLineAndCharacterOfPosition(member.getStart(source)).line + 1;
          found.push({ id: node.name.text, line, message: `${describe(member, placed)} comes after ${describe(highest.member, highest.placed)}` });
          break;
        }
        if (!highest || placed.order > highest.placed.order) highest = { member, placed };
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return found;
}

/** Rewrites each out-of-order Angular class of a file with its members stably sorted. */
function fixed(file, text) {
  const source = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true);
  const edits = [];
  const visit = node => {
    if (ts.isClassDeclaration(node) && node.name && isAngular(node) && node.members.length) {
      const members = [...node.members];
      const order = member => rank(member)?.order ?? 999;
      const sorted = members.map((member, index) => ({ member, index }))
        .sort((a, b) => order(a.member) - order(b.member) || a.index - b.index).map(entry => entry.member);
      if (sorted.some((member, index) => member !== members[index])) {
        const start = members[0].getFullStart();
        const end = members[members.length - 1].getEnd();
        // A blank line where the group changes or where the author had one; comments stay on their member.
        const pieces = sorted.map((member, index) => {
          const full = text.slice(member.getFullStart(), member.getEnd());
          const body = full.replace(/^([ \t]*\r?\n)+/, '');
          const previous = sorted[index - 1];
          const blank = previous && (rank(previous)?.group !== rank(member)?.group || /\n[ \t]*\r?\n/.test(full.slice(0, full.length - body.length)));
          return (blank ? '\n\n' : '\n') + body;
        });
        edits.push({ start, end, text: pieces.join('') });
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return edits.sort((a, b) => b.start - a.start).reduce((result, edit) => result.slice(0, edit.start) + edit.text + result.slice(edit.end), text);
}

const fixIndex = process.argv.indexOf('--fix');
if (fixIndex >= 0) {
  const { writeFile } = await import('node:fs/promises');
  for (const file of process.argv.slice(fixIndex + 1)) {
    const text = await readFile(file, 'utf8');
    const result = fixed(file, text);
    if (result !== text) {
      await writeFile(file, result);
      process.stdout.write(`Reordered ${file}\n`);
    }
  }
  process.exit(0);
}

const baseline = new Set((await readFile(baselineFile, 'utf8').catch(() => ''))
  .split(/\r?\n/).map(line => line.replace(/#.*/, '').trim()).filter(Boolean));
const problems = [];
const current = new Set();
for (const file of await sources(appRoot)) {
  const relative = path.relative(webRoot, file).split(path.sep).join('/');
  for (const violation of violations(file, await readFile(file, 'utf8'))) {
    const entry = `${relative} ${violation.id}`;
    current.add(entry);
    if (!baseline.has(entry)) problems.push(`${relative}:${violation.line} ${violation.id}: ${violation.message}`);
  }
}
const stale = [...baseline].filter(entry => !current.has(entry));

if (process.argv.includes('--write-baseline')) {
  const header = '# Angular classes that broke the member order before signal-order-audit existed.\n'
    + '# Put a class in order and delete its line; never add one. Regenerate: npm run signals:audit -- --write-baseline\n';
  const { writeFile } = await import('node:fs/promises');
  await writeFile(baselineFile, header + [...current].sort().join('\n') + '\n');
  process.stdout.write(`Baseline written: ${current.size} classes.\n`);
  process.exit(0);
}
if (problems.length || stale.length) {
  if (problems.length) process.stderr.write(`Class members out of order (inject → inputs → outputs → models → queries → signal → computed → effect → fields → constructor → methods; public → protected → private):\n${problems.join('\n')}\n`);
  if (stale.length) process.stderr.write(`Now in order — delete from scripts/signal-order-baseline.txt:\n${stale.join('\n')}\n`);
  process.exit(1);
}
process.stdout.write(`Signal order audit passed: ${current.size} classes still listed in the baseline.\n`);
