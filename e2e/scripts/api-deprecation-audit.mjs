// Fails when the e2e suite uses an API form the server has deprecated (plan 10/10, item 3.4, ADR-0023): a test
// that drives or waits for a deprecated path keeps passing after the path is removed only by luck.
//
// The deprecated operations and query parameters come from docs/api/openapi.json; the matching is shared with the
// web audit (scripts/api/api-deprecations.mjs). Checked in every TypeScript file of the suite, specs included:
//   - a call with a literal path (request.get('/api/v1/...'), api.post(`/api/v1/x/${id}`)): by method and path;
//   - any other literal API path (a route glob, a URL a test waits for): when every method reaching it reaches a
//     deprecated operation, as the method is not visible there;
//   - a query parameter named in a literal path (?project_id=) or in a `params: { ... }` object;
//   - a call with a literal path that no operation answers any more (a removed form), by method and path.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import {
  deprecatedCalls,
  lineOf,
  loadDeprecatedApi,
  requestedPath,
  resolveAnyMethod,
  typeScriptSources,
  unknownCalls,
} from '../../scripts/api/api-deprecations.mjs';

const e2eRoot = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const api = await loadDeprecatedApi();

/** Files that call another service (Mailpit also answers under /api/v1): their calls are not the server's. */
const OTHER_SERVICES = new Set(['support/mailpit.ts']);

/** A quoted literal that holds an API path, with an optional route-glob prefix ('**' + '/api/v1/...'). */
const API_LITERAL = /(['"`])(?:\*\*)?(\/api\/v1\/(?:(?!\1)[^\s*])*)\*?\1/g;
const QUERY_NAME = /[?&]([a-z0-9]+(?:_[a-z0-9]+)+)=/g;
const PARAMS_OBJECT = /\bparams\s*:\s*\{([^}]*)\}/g;
const KEY = /(?:^|[{,\s])['"]?([a-z0-9]+(?:_[a-z0-9]+)+)['"]?\s*:/gm;

const problems = [];
for (const file of await typeScriptSources(e2eRoot, { withSpecs: true })) {
  const text = await readFile(file, 'utf8');
  const relative = path.relative(e2eRoot, file).replace(/\\/g, '/');
  const calls = deprecatedCalls(api, text, relative);
  problems.push(...calls.problems);
  if (!OTHER_SERVICES.has(relative)) problems.push(...unknownCalls(api, text, relative));
  const reported = new Set(calls.problems.map((problem) => problem.split(' ')[0]));

  for (const match of text.matchAll(API_LITERAL)) {
    const literal = match[2];
    const where = `${relative}:${lineOf(text, match.index)}`;
    const reached = resolveAnyMethod(api.operations, requestedPath(literal));
    if (reached && !reported.has(where)) {
      problems.push(`${where} ${literal} is deprecated; use ${reached.successor ?? 'its successor'}`);
    }
    for (const name of literal.matchAll(QUERY_NAME)) {
      if (api.parameters.has(name[1])) {
        problems.push(`${where} query parameter ${name[1]} is deprecated; use its camelCase name`);
      }
    }
  }

  for (const object of text.matchAll(PARAMS_OBJECT)) {
    for (const key of object[1].matchAll(KEY)) {
      if (api.parameters.has(key[1])) {
        problems.push(
          `${relative}:${lineOf(text, object.index)} query parameter ${key[1]} is deprecated; use its camelCase name`,
        );
      }
    }
  }
}

if (problems.length > 0) {
  console.error(`The e2e suite uses deprecated or removed API forms (ADR-0023):\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
console.log(
  `E2E API deprecation audit: no use of ${api.deprecatedCount} deprecated operations, ` +
    `${api.parameters.size} deprecated parameters or a path no operation answers.`,
);
