#!/usr/bin/env node
// Runs the documentation contract (plan 10/10, item 6.6) over the cookbook and the module guide: fails with the list of
// names the documents mention and the repository does not have. Usage: node scripts/docs/test-docs-contract.mjs
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { run } from './docs-contract.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const { checked, problems } = await run(root);
for (const { file, line, span, reason } of problems) {
  console.error(`${file}:${line}: \`${span}\` - ${reason}`);
}
if (problems.length) {
  console.error(`Documentation contract: ${problems.length} problem(s) in ${checked.length} document(s).`);
  process.exitCode = 1;
} else {
  console.log(`Documentation contract: ${checked.length} document(s), every name exists.`);
}
