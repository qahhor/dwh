// The documentation contract (plan 10/10, item 6.6): what the cookbook, the module guide, CODE_STYLE and ADR-0012
// name in code spans and code blocks exists in the repository - Java and TypeScript types and their members, builder
// methods, files, cms commands and their options, Angular selectors, Checkstyle modules and entity codes - and every
// block marked
// `<!-- from: <file> -->` is taken from that file, line by line in order. A name a document uses only as an
// illustration is declared in the document: `<!-- docs-contract: hypothetical inventory.items, InventoryApi -->`.
// Node standard library only.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

/** The documents under contract, relative to the repository root: a directory means its *.md files. */
export const DOCUMENTS = [
  'docs/cookbook',
  'docs/guidelines/module-development-guide.md',
  'CODE_STYLE.md',
  'docs/adr/ADR-0012-ui-foundation.md',
];

/** Where the index reads sources; generated and installed trees are skipped. */
const SOURCE_ROOTS = ['apps/server', 'apps/web', 'libs', 'examples', 'tools', 'e2e', 'scripts'];
const DOC_ROOTS = ['docs', '.github', 'config', 'deploy', '.devcontainer'];
const SKIPPED_DIRS = new Set(['node_modules', 'target', 'dist', '.angular', 'coverage', 'test-results', 'baseline']);
const ROOT_FILES = ['README.md', 'AGENTS.md', 'CLAUDE.md', 'CODE_STYLE.md', 'CHANGELOG.md', 'Makefile', 'pom.xml', 'mvnw', 'mvnw.cmd'];

/** Words in code spans that look like types but name no code. */
const NOT_CODE = new Set(['ETag', 'OpenAPI', 'PostgreSQL', 'JavaScript', 'TypeScript', 'GitHub', 'SemVer', 'JUnit', 'ArchUnit']);

/** File extensions: a span ending in one is a file, and `ru.json` is not an entity code. */
const EXTENSIONS = new Set([
  'java', 'ts', 'mjs', 'js', 'md', 'json', 'sql', 'ps1', 'sh', 'yml', 'yaml', 'html', 'css', 'csv', 'xml', 'sha256',
  'properties', 'txt', 'xlsx', 'jar', 'scss',
]);

/** Roots a repository path starts with. */
const PATH_ROOTS = /^(apps|libs|docs|tools|scripts|examples|e2e|config|deploy|\.github|\.devcontainer)\//;

const posix = (p) => p.split(path.sep).join('/');

/** A call or a declaration of a method or function, generic ones too: `output<void>()`. */
const CALL = /\b([a-z]\w*)\s*(?:<[^<>()]*>)?\s*\(/g;

/** Every file under the roots, relative and with forward slashes. */
function walk(root, start, out) {
  const dir = path.join(root, start);
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) continue;
    const relative = posix(path.join(start, entry.name));
    if (entry.isDirectory()) {
      if (!SKIPPED_DIRS.has(entry.name)) walk(root, relative, out);
    } else {
      out.push(relative);
    }
  }
}

/** The cms command table: commands with their options, from the CLI itself. */
async function cliTable(root) {
  const main = await import(pathToFileURL(path.join(root, 'tools/cms-cli/lib/main.mjs')).href);
  const args = await import(pathToFileURL(path.join(root, 'tools/cms-cli/lib/args.mjs')).href);
  const commands = new Map();
  for (const [command, options] of Object.entries(main.OPTIONS)) {
    commands.set(command, new Set([...Object.keys(options), ...Object.keys(args.COMMON)]));
  }
  return commands;
}

/** What the documents may name, read from the repository. */
export async function buildIndex(root) {
  const files = [];
  for (const start of [...SOURCE_ROOTS, ...DOC_ROOTS]) walk(root, start, files);
  for (const file of ROOT_FILES) if (fs.existsSync(path.join(root, file))) files.push(file);
  const index = {
    files: new Set(files),
    dirs: new Set(),
    types: new Map(),
    tsSymbols: new Set(),
    calls: new Set(),
    selectors: new Set(),
    codes: new Set(),
    artifacts: new Set(),
    cli: await cliTable(root),
  };
  // The modules of the Checkstyle configuration (FileLength) are rules a document names.
  const checkstyle = path.join(root, 'config/checkstyle/checkstyle.xml');
  if (fs.existsSync(checkstyle)) {
    for (const match of fs.readFileSync(checkstyle, 'utf8').matchAll(/<module name="(\w+)"/g)) {
      index.tsSymbols.add(match[1]);
    }
  }
  // The schemas of the API description (ExampleRequestsRecord) are names a client uses.
  const openapi = path.join(root, 'docs/api/openapi.json');
  if (fs.existsSync(openapi)) {
    for (const name of Object.keys(JSON.parse(fs.readFileSync(openapi, 'utf8')).components?.schemas ?? {})) {
      index.tsSymbols.add(name);
    }
  }
  for (const file of files) {
    const parts = file.split('/');
    for (let i = 1; i < parts.length; i++) index.dirs.add(parts.slice(0, i).join('/'));
  }
  const read = (file) => fs.readFileSync(path.join(root, file), 'utf8');
  for (const file of files) {
    if (file.endsWith('.java')) indexJava(index, file, read(file));
    else if (/\.(ts|mjs)$/.test(file) && !file.endsWith('.d.ts')) indexTypeScript(index, file, read(file));
    else if (/db\/migration\/V\d+__.*\.sql$/.test(file)) indexForms(index, read(file));
    else if (file === 'pom.xml' || file.endsWith('/pom.xml')) {
      for (const match of read(file).matchAll(/<artifactId>([\w.-]+)<\/artifactId>/g)) index.artifacts.add(match[1]);
    }
  }
  return index;
}

function indexJava(index, file, text) {
  for (const match of text.matchAll(/\b(?:class|interface|record|enum|@interface)\s+([A-Z]\w*)/g)) {
    if (!index.types.has(match[1])) index.types.set(match[1], []);
    index.types.get(match[1]).push(file);
  }
  for (const match of text.matchAll(CALL)) index.calls.add(match[1]);
  // A type of a library the module imports (ObjectMapper) exists on its classpath.
  for (const match of text.matchAll(/^import (?:static )?[\w.]*?\.([A-Z]\w*)(?:\.[\w*]+)?;/gm)) {
    index.tsSymbols.add(match[1]);
  }
  for (const match of text.matchAll(/\bCODE\s*=\s*"([a-z][\w.]*)"/g)) index.codes.add(match[1]);
  for (const match of text.matchAll(/Entity\.define\(\s*"([a-z][\w.]*)"/g)) index.codes.add(match[1]);
  for (const match of text.matchAll(/\b(?:public static final String|String)\s+[A-Z_]+\s*=\s*"([a-z]+\.[a-z_]+)"/g)) {
    index.codes.add(match[1]);
  }
  if (file.endsWith('PermissionAreas.java')) {
    for (const match of text.matchAll(/"([a-z][a-z_.]*)"/g)) index.codes.add(match[1]);
  }
}

function indexTypeScript(index, file, text) {
  for (const match of text.matchAll(
    /\bexport\s+(?:default\s+)?(?:abstract\s+)?(?:class|interface|type|function|const|let|enum)\s+([A-Za-z_]\w*)/g,
  )) {
    index.tsSymbols.add(match[1]);
  }
  for (const match of text.matchAll(CALL)) index.calls.add(match[1]);
  // Names imported from a package (inject, ChangeDetectionStrategy) and members used as Type.Member (OnPush).
  for (const match of text.matchAll(/^import\s*(?:type\s*)?\{([^}]*)\}\s*from/gm)) {
    for (const name of match[1].split(',')) if (name.trim()) index.tsSymbols.add(name.trim().split(/\s+as\s+/)[0]);
  }
  for (const match of text.matchAll(/\b[A-Z]\w*\.([A-Z]\w*)\b/g)) index.tsSymbols.add(match[1]);
  for (const match of text.matchAll(/selector:\s*'([^']+)'/g)) {
    for (const part of match[1].split(',')) {
      const element = /^\s*([a-z][\w-]*)/.exec(part);
      if (element) index.selectors.add(element[1]);
      for (const attribute of part.matchAll(/\[([\w-]+)\]/g)) index.selectors.add(attribute[1]);
    }
  }
}

function indexForms(index, text) {
  for (const statement of text.split(';')) {
    if (!/insert into md_forms\b/i.test(statement)) continue;
    for (const match of statement.matchAll(/\('([a-z][\w]*\.[\w]+)',/g)) index.codes.add(match[1]);
  }
}

/** The *.md files of the documents under contract. */
export function documentFiles(root, documents = DOCUMENTS) {
  const out = [];
  for (const document of documents) {
    const full = path.join(root, document);
    if (!fs.existsSync(full)) continue;
    if (fs.statSync(full).isDirectory()) {
      for (const name of fs.readdirSync(full).sort()) if (name.endsWith('.md')) out.push(`${document}/${name}`);
    } else {
      out.push(document);
    }
  }
  return out;
}

/** The names a document declares as illustrations only. */
function hypotheticals(text) {
  const names = new Set();
  for (const match of text.matchAll(/<!--\s*docs-contract:\s*hypothetical\s+([^>]*?)-->/g)) {
    for (const name of match[1].split(/[\s,]+/)) if (name) names.add(name);
  }
  return names;
}

/**
 * The problems of one markdown document: each `{ file, line, span, reason }`. `readFile` reads a repository file for
 * the blocks marked `<!-- from: <file> -->`.
 */
export function checkMarkdown(file, text, index, readFile) {
  const problems = [];
  const illustrations = hypotheticals(text);
  const report = (line, span, reason) => problems.push({ file, line, span, reason });
  const lines = text.split(/\r?\n/);
  let fence = null;
  let lastText = '';
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const opening = /^\s*(```|~~~)\s*([\w-]*)/.exec(line);
    if (fence) {
      if (opening && opening[1] === fence.marker && !opening[2]) {
        checkBlock(fence, index, readFile, illustrations, report);
        fence = null;
        lastText = '';
      } else {
        fence.lines.push({ text: line, number: i + 1 });
      }
      continue;
    }
    if (opening) {
      const from = /<!--\s*from:\s*(\S+)\s*-->/.exec(lastText);
      fence = { marker: opening[1], lang: opening[2], from: from ? from[1] : null, start: i + 1, lines: [] };
      continue;
    }
    if (line.trim()) lastText = line;
    for (const match of line.matchAll(/\]\(([^)#\s]+)(?:#[^)]*)?\)/g)) {
      if (/^[a-z]+:/.test(match[1])) continue;
      const target = path.posix.normalize(path.posix.join(path.posix.dirname(file), match[1])).replace(/\/$/, '');
      if (!index.files.has(target) && !index.dirs.has(target)) report(i + 1, match[1], 'the link leads nowhere');
    }
    for (const match of line.matchAll(/`([^`]+)`/g)) {
      const reason = checkSpan(match[1].trim(), index, illustrations);
      if (reason) report(i + 1, match[1], reason);
    }
  }
  if (fence) report(fence.start, fence.marker, 'the code block is not closed');
  return problems;
}

/** A code block: its cms command lines, its Angular tags and, when marked, its sync with the file it is taken from. */
function checkBlock(block, index, readFile, illustrations, report) {
  for (const { text, number } of block.lines) {
    const command = /^\s*(?:\$\s+)?((?:cms|node tools\/cms-cli\/bin\/cms\.mjs)\s.*?)(?:\s+#.*)?$/.exec(text);
    if (command && ['bash', 'sh', 'shell', 'powershell', 'ps1', 'text', ''].includes(block.lang)) {
      const reason = checkCommand(command[1], index);
      if (reason) report(number, command[1], reason);
    }
    if (['html', 'ts', 'typescript'].includes(block.lang)) {
      for (const tag of text.matchAll(/<((?:smt|ui|app)-[a-z0-9-]+)/g)) {
        if (!index.selectors.has(tag[1]) && !illustrations.has(tag[1])) report(number, tag[1], 'no Angular selector');
      }
    }
  }
  if (!block.from) return;
  const source = readFile(block.from);
  if (source === null) {
    report(block.start, block.from, 'the file the block is taken from does not exist');
    return;
  }
  const wanted = block.lines
    .map(({ text, number }) => ({ text: text.trim(), number }))
    .filter(({ text }) => text && !/^(\/\/|--|#|<!--)?\s*(\.\.\.|…)\s*(-->)?$/.test(text));
  const have = source.split(/\r?\n/).map((line) => line.trim());
  let at = 0;
  for (const { text, number } of wanted) {
    const found = have.indexOf(text, at);
    if (found < 0) {
      report(number, text, `not in ${block.from} (after the lines before it): copy the block from the file again`);
      return;
    }
    at = found + 1;
  }
}

/** A cms command line: a known command, and only its options. */
export function checkCommand(line, index) {
  const words = line
    .replace(/^node tools\/cms-cli\/bin\/cms\.mjs/, 'cms')
    .split(/\s+/)
    .filter(Boolean);
  if (words.slice(1, 3).some((word) => word.startsWith('<'))) return null;
  const command = index.cli.has(`${words[1]} ${words[2]}`) ? `${words[1]} ${words[2]}` : words[1];
  const options = index.cli.get(command);
  if (!options) return `no cms command "${words.slice(1, 3).join(' ')}"`;
  for (const word of words) {
    const option = /^--([a-z][\w-]*)/.exec(word);
    if (option && !options.has(option[1])) return `cms ${command} has no option --${option[1]}`;
  }
  return null;
}

/** Why a code span names nothing in the repository, or null. */
export function checkSpan(span, index, illustrations = new Set()) {
  if (illustrations.has(span)) return null;
  if (/^(?:cms|node tools\/cms-cli\/bin\/cms\.mjs)\s/.test(span)) return checkCommand(span, index);
  const bare = span.replace(/\([^()]*\)/g, '()').replace(/\([^()]*\)/g, '()');
  if (/[<>{}*…|]|\.\.\.|^\//.test(bare)) return null;
  if (/\s/.test(bare)) return null;
  if (bare.includes('/')) return checkPath(bare, index);
  const coordinate = /^([a-z][\w.]*):([\w.-]+)$/.exec(bare);
  if (coordinate) {
    if (!coordinate[1].startsWith('com.smartup24')) return null;
    return index.artifacts.has(coordinate[2]) ? null : `no Maven artifact ${coordinate[2]}`;
  }
  if (/^(?:smt|ui|app)-[a-z0-9-]+$/.test(bare)) return index.selectors.has(bare) ? null : 'no Angular selector';
  if (/^smt[A-Z]\w*$/.test(bare)) return index.selectors.has(bare) ? null : 'no Angular directive';
  const code = /^([a-z][a-z0-9_]*)\.([a-z][a-z0-9_]*)$/.exec(bare);
  if (code) {
    if (EXTENSIONS.has(code[2])) return null;
    const prefixes = new Set([...index.codes].map((c) => c.split('.')[0]));
    if (!prefixes.has(code[1])) return null;
    return index.codes.has(bare) ? null : 'no entity, form or module code';
  }
  return checkSymbol(bare, index, illustrations);
}

function checkPath(span, index) {
  const clean = span.replace(/\/$/, '').replace(/^\.\//, '');
  const segments = clean.split('/');
  if (segments.some((s) => s === '..' || s === '.')) return null;
  if (!/^[\w.@-]+(\/[\w.@-]+)+$/.test(clean)) return null;
  const extension = /\.([a-z0-9]+)$/.exec(segments.at(-1));
  const hasExtension = extension && EXTENSIONS.has(extension[1]);
  if (!hasExtension && !PATH_ROOTS.test(clean)) return null;
  if (index.files.has(clean) || index.dirs.has(clean)) return null;
  if (!PATH_ROOTS.test(clean)) {
    for (const file of index.files) if (file.endsWith(`/${clean}`)) return null;
    for (const dir of index.dirs) if (dir.endsWith(`/${clean}`)) return null;
  }
  return 'no such file or directory';
}

function checkSymbol(span, index, illustrations) {
  if (/^(?:com|org)\.[a-z]|^[a-z]+(\.[a-z]+)+\.[A-Z]/.test(span)) return checkQualified(span, index);
  const symbol = /^(\.)?([A-Za-z_]\w*)((?:[.#][A-Za-z_]\w*)*)(\(\))?((?:\.[A-Za-z_]\w*(?:\(\))?)*)$/.exec(span);
  if (!symbol) return null;
  const [, builder, head, tail, call] = symbol;
  if (builder) return !call || index.calls.has(head) ? null : `no method ${head}`;
  const known = (name) => index.types.has(name) || index.tsSymbols.has(name);
  const typeLike = /^[A-Z][a-z0-9]+[A-Z]\w*$/.test(head) || /^[A-Z]{2,}[a-z]\w*$/.test(head);
  if (/^[A-Z]/.test(head)) {
    if (!known(head)) {
      if (NOT_CODE.has(head) || illustrations.has(head) || !typeLike) return null;
      return `no Java or TypeScript type ${head}`;
    }
    const members = tail.split(/[.#]/).filter(Boolean);
    let owner = head;
    for (const member of members) {
      if (/^[A-Z][a-z]/.test(member) && index.types.has(member)) {
        owner = member;
        continue;
      }
      const files = index.types.get(owner) ?? [];
      if (files.length && !files.some((file) => index.read(file).match(new RegExp(`\\b${member}\\b`)))) {
        return `${owner} has no member ${member}`;
      }
    }
    return null;
  }
  if (call && !tail) return index.calls.has(head) || known(head) ? null : `no method ${head}`;
  return null;
}

/** `com.smartup24.cms.platform.api..` (a package) or `common.entity.EntitySchemaCheck` (a type in a package). */
function checkQualified(span, index) {
  const parts = span.replace(/\(\)$/, '').replace(/\.+$/, '').split('.');
  const typeAt = parts.findIndex((part) => /^[A-Z]/.test(part));
  const packagePath = (typeAt < 0 ? parts : parts.slice(0, typeAt)).join('/');
  if (typeAt < 0) {
    for (const dir of index.dirs) if (dir.endsWith(`/${packagePath}`)) return null;
    return `no package ${parts.join('.')}`;
  }
  const files = index.types.get(parts[typeAt]) ?? [];
  return files.some((file) => file.includes(`/${packagePath}/`)) ? null : `no type ${parts.slice(0, typeAt + 1).join('.')}`;
}

/** Runs the contract over the documents; returns the problems. */
export async function run(root, documents = DOCUMENTS) {
  const index = await buildIndex(root);
  const cache = new Map();
  index.read = (file) => {
    if (!cache.has(file)) cache.set(file, fs.readFileSync(path.join(root, file), 'utf8'));
    return cache.get(file);
  };
  const readFile = (file) => (fs.existsSync(path.join(root, file)) ? index.read(file) : null);
  const problems = [];
  const checked = documentFiles(root, documents);
  for (const file of checked) {
    problems.push(...checkMarkdown(file, fs.readFileSync(path.join(root, file), 'utf8'), index, readFile));
  }
  return { checked, problems };
}
