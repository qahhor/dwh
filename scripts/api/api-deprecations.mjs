// The deprecated API forms of docs/api/openapi.json and how a source file's calls are matched against them (plan
// 10/10, item 3.4, ADR-0023). Shared by the audits of the web (apps/web/scripts/api-deprecation-audit.mjs) and of the
// e2e suite (e2e/scripts/api-deprecation-audit.mjs); this module checks nothing by itself.
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const API_PREFIX = '/api/v1';
const METHODS = ['get', 'post', 'put', 'patch', 'delete'];

/** The generated description, found from this module so the callers may run from any directory. */
export const SPEC_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'docs',
  'api',
  'openapi.json',
);

/** this.api.get<T>('/x', ...), request.post(`/api/v1/x/${id}`, ...): the method and the literal path. */
export const CALL = /\.(get|post|put|patch|delete)\s*(?:<(?:[^<>]|<[^<>]*>)*>)?\s*\(\s*(['`])((?:(?!\2).)*)\2/g;

/** A path template as a pattern over the callers' paths: a variable, or an interpolation, matches one segment. */
function pattern(template) {
  const relative = template.startsWith(API_PREFIX) ? template.slice(API_PREFIX.length) : template;
  const body = relative
    .split('/')
    .map((segment) => (/^\{[^}]+\}$/.test(segment) ? '[^/]+' : segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    .join('/');
  return new RegExp(`^(?:${API_PREFIX.replace(/\//g, '\\/')})?${body}$`);
}

/** How many segments of a template are literal: the more, the more specific its match. */
function literalSegments(template) {
  return template.split('/').filter((segment) => segment && !/^\{[^}]+\}$/.test(segment)).length;
}

function entry(method, template, deprecated, successor) {
  return {
    method,
    template,
    deprecated,
    successor,
    regex: pattern(template),
    literals: literalSegments(template),
  };
}

/**
 * The operation a call reaches: of those whose template matches the path for the method, the one with the most
 * literal segments, as the server routes it (GET /tasks/items is the alias, not GET /tasks/{id}); a tie goes to the
 * current one.
 */
export function resolve(operations, method, requested) {
  let best = null;
  for (const candidate of operations) {
    if (candidate.method !== method || !candidate.regex.test(requested)) continue;
    const moreSpecific = best === null || candidate.literals > best.literals;
    const currentOnTie =
      best !== null && candidate.literals === best.literals && best.deprecated && !candidate.deprecated;
    if (moreSpecific || currentOnTie) best = candidate;
  }
  return best;
}

/**
 * The operation a path reaches when the method is not visible (a route glob, an awaited URL): deprecated only when
 * every method that reaches the path reaches a deprecated operation, so a path that is current for one method is
 * never reported. Null when the path is current or unknown.
 */
export function resolveAnyMethod(operations, requested) {
  const reached = METHODS.map((method) => resolve(operations, method, requested)).filter(Boolean);
  return reached.length > 0 && reached.every((operation) => operation.deprecated) ? reached[0] : null;
}

/** The literal path of a call as the operations are matched: no query, an interpolation as one segment. */
export function requestedPath(literal) {
  return literal.split('?')[0].replace(/\$\{[^}]*\}/g, '{value}');
}

// Self-check of the resolution on the case that once passed unnoticed: a literal alias beside a variable route.
const sample = [
  entry('get', '/api/v1/tasks/{id}', false),
  entry('get', '/api/v1/tasks/items', true, 'GET /api/v1/tasks'),
  entry('post', '/api/v1/tasks/items/{id}/pin', true, 'PUT /api/v1/tasks/items/{id}/pin'),
  entry('put', '/api/v1/tasks/items/{id}/pin', false),
];
if (
  resolve(sample, 'get', '/tasks/items')?.deprecated !== true ||
  resolve(sample, 'get', '/api/v1/tasks/{value}')?.deprecated !== false ||
  resolve(sample, 'post', '/tasks/items') !== null ||
  resolveAnyMethod(sample, '/api/v1/tasks/items')?.deprecated !== true ||
  resolveAnyMethod(sample, '/api/v1/tasks/items/7/pin') !== null
) {
  throw new Error('api-deprecations: the self-check of the path resolution failed');
}

/** The operations of the description, with the names of the deprecated query parameters. */
export async function loadDeprecatedApi(specPath = SPEC_PATH) {
  const spec = JSON.parse(await readFile(specPath, 'utf8'));
  const operations = [];
  const parameters = new Set();
  for (const [template, item] of Object.entries(spec.paths)) {
    for (const [method, operation] of Object.entries(item)) {
      if (!METHODS.includes(method)) continue;
      operations.push(entry(method, template, operation.deprecated === true, operation['x-successor']));
      for (const parameter of operation.parameters ?? []) {
        if (parameter.in === 'query' && parameter.deprecated) parameters.add(parameter.name);
      }
    }
  }
  const deprecatedCount = operations.filter((operation) => operation.deprecated).length;
  if (deprecatedCount === 0) {
    throw new Error('docs/api/openapi.json lists no deprecated operation: is it the generated description?');
  }
  return { operations, parameters, deprecatedCount };
}

/** The TypeScript sources under a directory, without specs and declarations unless `withSpecs`. */
export async function typeScriptSources(directory, { withSpecs = false } = {}) {
  const found = [];
  for (const item of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, item.name);
    if (item.isDirectory()) {
      if (item.name !== 'node_modules') found.push(...(await typeScriptSources(absolute, { withSpecs })));
    } else if (
      item.name.endsWith('.ts') &&
      !item.name.endsWith('.d.ts') &&
      (withSpecs || !item.name.endsWith('.spec.ts'))
    ) {
      found.push(absolute);
    }
  }
  return found;
}

/** The line of an offset in a text, counted from one. */
export function lineOf(text, index) {
  return text.slice(0, index).split('\n').length;
}

/** Every call whose method and literal path reach a deprecated operation, as report lines. */
export function deprecatedCalls(api, text, relative) {
  const problems = [];
  let callsApi = false;
  for (const match of text.matchAll(CALL)) {
    const [, method, , literal] = match;
    const requested = requestedPath(literal);
    if (!requested.startsWith('/')) continue;
    callsApi = true;
    // A path that is current for this method is fine even if an alias of it is deprecated for another.
    const reached = resolve(api.operations, method, requested);
    if (reached?.deprecated) {
      problems.push(
        `${relative}:${lineOf(text, match.index)} ${method.toUpperCase()} ${literal} is deprecated; ` +
          `use ${reached.successor ?? 'its successor'}`,
      );
    }
  }
  return { problems, callsApi };
}
