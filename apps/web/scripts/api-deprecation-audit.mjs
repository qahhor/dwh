// Fails when the web calls an API form the server has deprecated (plan 10/10, item 3.4, ADR-0023).
//
// The deprecated operations and query parameters are read from docs/api/openapi.json, the description generated
// from the controllers: a path alias, a toggle replaced by a PUT, a snake_case parameter. Every call of ApiService
// (api.get/post/put/patch/delete) and of HttpClient whose path is a literal is matched against them by method and
// path; every object key of a file that calls the API is matched against the deprecated parameter names. A path
// built at run time is not visible to this scan; the e2e suite exercises the screens against the real server.
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const appRoot = path.join(process.cwd(), 'src', 'app');
const spec = JSON.parse(await readFile(path.join(process.cwd(), '..', '..', 'docs', 'api', 'openapi.json'), 'utf8'));
const API_PREFIX = '/api/v1';

/** A path template as a pattern over the web's paths: a variable, or an interpolation, matches one segment. */
function pattern(template) {
  const relative = template.startsWith(API_PREFIX) ? template.slice(API_PREFIX.length) : template;
  const body = relative
    .split('/')
    .map((segment) => (/^\{[^}]+\}$/.test(segment) ? '[^/]+' : segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    .join('/');
  return new RegExp(`^(?:${API_PREFIX.replace(/\//g, '\\/')})?${body}$`);
}

const deprecatedOperations = [];
const currentOperations = [];
const deprecatedParameters = new Set();
for (const [template, item] of Object.entries(spec.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) continue;
    const entry = { method, template, regex: pattern(template), successor: operation['x-successor'] };
    (operation.deprecated ? deprecatedOperations : currentOperations).push(entry);
    for (const parameter of operation.parameters ?? []) {
      if (parameter.in === 'query' && parameter.deprecated) deprecatedParameters.add(parameter.name);
    }
  }
}

async function sources(directory) {
  const found = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) found.push(...(await sources(absolute)));
    else if (entry.name.endsWith('.ts') && !entry.name.endsWith('.spec.ts') && !entry.name.endsWith('.d.ts'))
      found.push(absolute);
  }
  return found;
}

// this.api.get<T>('/x', ...), this.http.post(`/api/v1/x/${id}`, ...): the method and the literal path.
const CALL = /\.(get|post|put|patch|delete)\s*(?:<(?:[^<>]|<[^<>]*>)*>)?\s*\(\s*(['`])((?:(?!\2).)*)\2/g;
const KEY = /(?:^|[{,\s])['"]?([a-z0-9]+(?:_[a-z0-9]+)+)['"]?\s*:/gm;

const problems = [];
for (const file of await sources(appRoot)) {
  const text = await readFile(file, 'utf8');
  const relative = path.relative(process.cwd(), file).replace(/\\/g, '/');
  let callsApi = false;
  for (const match of text.matchAll(CALL)) {
    const [, method, , literal] = match;
    const requested = literal.split('?')[0].replace(/\$\{[^}]*\}/g, '{value}');
    if (!requested.startsWith('/')) continue;
    callsApi = true;
    // A path that is current for this method is fine even if an alias of it is deprecated for another.
    if (currentOperations.some((op) => op.method === method && op.regex.test(requested))) continue;
    const deprecated = deprecatedOperations.find((op) => op.method === method && op.regex.test(requested));
    if (deprecated) {
      const line = text.slice(0, match.index).split('\n').length;
      problems.push(
        `${relative}:${line} ${method.toUpperCase()} ${literal} is deprecated; use ${deprecated.successor ?? 'its successor'}`,
      );
    }
  }
  if (!callsApi) continue;
  for (const match of text.matchAll(KEY)) {
    if (deprecatedParameters.has(match[1])) {
      const line = text.slice(0, match.index).split('\n').length;
      problems.push(`${relative}:${line} query parameter ${match[1]} is deprecated; use its camelCase name`);
    }
  }
}

if (deprecatedOperations.length === 0) {
  console.error('docs/api/openapi.json lists no deprecated operation: is it the generated description?');
  process.exit(1);
}
if (problems.length > 0) {
  console.error(`The web calls deprecated API forms (ADR-0023):\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
console.log(
  `API deprecation audit: no call of ${deprecatedOperations.length} deprecated operations and ` +
    `${deprecatedParameters.size} deprecated parameters.`,
);
