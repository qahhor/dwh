// The documentation contract's own tests (plan 10/10, item 6.6): it fails on each kind of name the repository does not
// have and passes on the ones it has, over a small index made by hand. node:test, no packages.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { checkMarkdown, checkSpan } from './docs-contract.mjs';

const FOO = 'apps/server/src/main/java/com/acme/service/Foo.java';
const SOURCES = {
  [FOO]: 'public class Foo {\n    public static final String CODE = "example.orders";\n    void run() {}\n}\n',
};

const index = {
  files: new Set([FOO, 'docs/cookbook/README.md']),
  dirs: new Set(['apps', 'apps/server', 'apps/server/src/main/java/com/acme/service', 'docs', 'docs/cookbook']),
  types: new Map([['Foo', [FOO]]]),
  tsSymbols: new Set(['provideEntityOverrides', 'SMTEntityFormComponent']),
  calls: new Set(['define', 'scope', 'provideEntityOverrides']),
  selectors: new Set(['smt-entity-form', 'smtEntityField']),
  codes: new Set(['example.orders', 'example']),
  artifacts: new Set(['platform-api']),
  cli: new Map([
    ['entity new', new Set(['title', 'hooks', 'root', 'dry-run', 'help'])],
    ['doctor', new Set(['root', 'dry-run', 'help'])],
  ]),
  read: (file) => SOURCES[file],
};
const readFile = (file) => SOURCES[file] ?? null;

describe('code spans', () => {
  test('names the repository has pass', () => {
    for (const span of [
      'Foo',
      'Foo.CODE',
      'Foo.run()',
      '.define(code, form)',
      'provideEntityOverrides(code, {cells})',
      'SMTEntityFormComponent',
      'smt-entity-form',
      'smtEntityField',
      'example.orders',
      FOO,
      'docs/cookbook/',
      'service/Foo.java',
      'com.acme.service',
      'com.acme.service.Foo',
      'cms entity new example items --title Items --hooks',
      'cms doctor',
      'ru.json',
      'ETag',
      '/e/<код>',
      'If-Match',
      'com.smartup24.cms:platform-api',
      'org.springframework:spring-context',
    ]) {
      assert.equal(checkSpan(span, index), null, span);
    }
  });

  test('each kind of missing name is reported', () => {
    const cases = {
      FooBarBaz: /no Java or TypeScript type FooBarBaz/,
      'Foo.missing': /Foo has no member missing/,
      '.nothing(x)': /no method nothing/,
      'smt-nothing': /no Angular selector/,
      smtNothing: /no Angular directive/,
      'example.nothing': /no entity, form or module code/,
      'apps/server/src/Missing.java': /no such file/,
      'com.acme.missing': /no package/,
      'cms entity new example items --colour red': /has no option --colour/,
      'cms frobnicate': /no cms command/,
      'com.smartup24.cms:platform-nothing': /no Maven artifact platform-nothing/,
    };
    for (const [span, reason] of Object.entries(cases)) {
      assert.match(checkSpan(span, index) ?? 'passed', reason, span);
    }
  });

  test('a name the document declares as an illustration passes', () => {
    assert.equal(checkSpan('InventoryApi', index, new Set(['InventoryApi'])), null);
    assert.match(checkSpan('InventoryApi', index), /no Java or TypeScript type/);
  });
});

describe('documents', () => {
  const doc = (body) => checkMarkdown('docs/cookbook/x.md', body, index, readFile);

  test('a block taken from a file matches it line by line, with elisions', () => {
    const body = [
      `<!-- from: ${FOO} -->`,
      '```java',
      'public class Foo {',
      '    // ...',
      '    void run() {}',
      '```',
    ].join('\n');
    assert.deepEqual(doc(body), []);
  });

  test('a block that left its file behind is reported at the line that differs', () => {
    const body = [`<!-- from: ${FOO} -->`, '```java', 'public class Foo {', '    void walk() {}', '```'].join('\n');
    const problems = doc(body);
    assert.equal(problems.length, 1);
    assert.equal(problems[0].line, 4);
    assert.match(problems[0].reason, /not in apps\/server/);
  });

  test('a block of a missing file, commands and tags in blocks, spans in text', () => {
    const body = [
      '<!-- docs-contract: hypothetical InventoryApi -->',
      'Text with `Foo.CODE`, `InventoryApi` and `Bar.Baz.Qux`, `MissingThing`.',
      '<!-- from: apps/none/Nothing.java -->',
      '```java',
      'class Nothing {}',
      '```',
      '```bash',
      'cms entity new example items --hooks   # fine',
      'cms entity new example items --nope',
      '```',
      '```html',
      '<smt-entity-form [meta]="m" />',
      '<smt-missing />',
      '```',
    ].join('\n');
    const reasons = doc(body).map((p) => `${p.line}:${p.reason}`);
    assert.deepEqual(reasons, [
      '2:no Java or TypeScript type MissingThing',
      '4:the file the block is taken from does not exist',
      '9:cms entity new has no option --nope',
      '13:no Angular selector',
    ]);
  });

  test('an unclosed block is reported', () => {
    assert.match(doc('```java\nclass A {}\n')[0].reason, /not closed/);
  });
});
