// Command line parsing on node:util parseArgs: positionals and --options, unknown options refused.
import { parseArgs } from 'node:util';
import { CliError } from './layout.mjs';

/** Options every command takes. */
export const COMMON = {
  root: { type: 'string' },
  'dry-run': { type: 'boolean' },
  help: { type: 'boolean', short: 'h' },
};

export function parse(argv, options) {
  try {
    return parseArgs({ args: argv, options: { ...COMMON, ...options }, allowPositionals: true, strict: true });
  } catch (error) {
    throw new CliError(error.message);
  }
}

/** A comma-separated option as a list. */
export function list(value) {
  return value
    ? String(value)
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean)
    : [];
}
