// Verify both the lexical path and its nearest existing ancestor before a Studio write or receipt check.
import fs from 'node:fs';
import path from 'node:path';
import { CliError } from './layout.mjs';

export function safePath(root, relative) {
  const base = path.resolve(root);
  const target = path.resolve(base, relative);
  const inside = (parent, child) => {
    const remainder = path.relative(parent, child);
    return !path.isAbsolute(remainder) && remainder !== '..' && !remainder.startsWith(`..${path.sep}`);
  };
  if (!inside(base, target)) throw new CliError('Path escapes the repository root');
  let ancestor = target;
  while (!fs.existsSync(ancestor)) {
    const parent = path.dirname(ancestor);
    if (parent === ancestor) throw new CliError('Invalid repository path');
    ancestor = parent;
  }
  if (!inside(fs.realpathSync(base), fs.realpathSync(ancestor))) {
    throw new CliError('Path resolves outside the repository root through a link');
  }
  return target;
}
