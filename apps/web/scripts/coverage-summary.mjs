// Prints the unit test coverage as a Markdown table and fails below the floor.
//
// `npm run test:coverage` writes coverage/web/coverage-summary.json; CI appends
// this table to the job summary, so every pull request shows its coverage
// (roadmap item 30, the idea of smartup-ui-kit's write-coverage-summary.js).
// The floor sits a little under today's figures: coverage may wander, but a
// change that drops it noticeably fails. Raise the floor when coverage grows;
// never lower it to let a change through.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const FLOOR = { lines: 83, statements: 80, functions: 65, branches: 69 };
const LABEL = { lines: 'Lines', statements: 'Statements', functions: 'Functions', branches: 'Branches' };

const reportPath = path.resolve(process.cwd(), process.argv[2] ?? 'coverage/web/coverage-summary.json');
let total;
try {
  total = JSON.parse(await readFile(reportPath, 'utf8')).total;
} catch {
  process.stderr.write(`Coverage summary not found at ${reportPath}; run npm run test:coverage first.\n`);
  process.exit(1);
}

const below = [];
const rows = Object.keys(FLOOR).map(metric => {
  const { pct, covered, total: all } = total[metric];
  const ok = pct >= FLOOR[metric];
  if (!ok) below.push(`${LABEL[metric]} ${pct}% < ${FLOOR[metric]}%`);
  return `| ${LABEL[metric]} | ${pct}% | ${covered}/${all} | ${FLOOR[metric]}% | ${ok ? 'ok' : 'below'} |`;
});

process.stdout.write([
  '## Web unit test coverage',
  '',
  '| Metric | Coverage | Covered/Total | Floor | |',
  '| --- | ---: | ---: | ---: | --- |',
  ...rows,
  ''
].join('\n') + '\n');

if (below.length) {
  process.stderr.write(`Coverage fell below the floor: ${below.join(', ')}.\n`);
  process.exit(1);
}
