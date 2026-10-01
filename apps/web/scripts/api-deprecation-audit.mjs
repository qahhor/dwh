// Fails when the web calls an API form the server has deprecated or no longer answers (plan 10/10, item 3.4,
// ADR-0023).
//
// The deprecated operations and query parameters are read from docs/api/openapi.json, the description generated
// from the controllers: a path alias, a toggle replaced by a PUT, a snake_case parameter. Every call of ApiService
// (api.get/post/put/patch/delete) and of HttpClient whose path is a literal is matched against them by method and
// path, and must reach some operation of the description (a removed form reaches none); every object key of a file
// that calls the API is matched against the deprecated parameter names. A path built at run time is not visible to
// this scan; the e2e suite exercises the screens against the real server. The matching is shared with the e2e audit
// (scripts/api/api-deprecations.mjs).
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import {
  deprecatedCalls,
  lineOf,
  loadDeprecatedApi,
  typeScriptSources,
  unknownCalls,
} from '../../../scripts/api/api-deprecations.mjs';

const appRoot = path.join(process.cwd(), 'src', 'app');
const api = await loadDeprecatedApi();

const KEY = /(?:^|[{,\s])['"]?([a-z0-9]+(?:_[a-z0-9]+)+)['"]?\s*:/gm;

const problems = [];
for (const file of await typeScriptSources(appRoot)) {
  const text = await readFile(file, 'utf8');
  const relative = path.relative(process.cwd(), file).replace(/\\/g, '/');
  const calls = deprecatedCalls(api, text, relative);
  problems.push(...calls.problems, ...unknownCalls(api, text, relative));
  if (!calls.callsApi) continue;
  for (const match of text.matchAll(KEY)) {
    if (api.parameters.has(match[1])) {
      problems.push(
        `${relative}:${lineOf(text, match.index)} query parameter ${match[1]} is deprecated; use its camelCase name`,
      );
    }
  }
}

if (problems.length > 0) {
  console.error(`The web calls deprecated or removed API forms (ADR-0023):\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
console.log(
  `API deprecation audit: no call of ${api.deprecatedCount} deprecated operations, ` +
    `${api.parameters.size} deprecated parameters or a path no operation answers.`,
);
