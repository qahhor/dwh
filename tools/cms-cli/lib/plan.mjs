// A command first plans every write, then writes all or nothing (plan 10/10, item 6.1). A file the command would
// create that already exists is never overwritten: other content is a user's edit (or a later command's) and is kept;
// a file that belongs to something else stops the command before it touches anything. Running a command again plans
// no change, so a re-run is safe.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { CliError, resolve } from './layout.mjs';
import { safePath } from './safe-path.mjs';

/** Text with `\n` line endings. */
export function normalize(text) {
  return text.replace(/\r\n/g, '\n');
}

/** The line ending a file uses, or the platform's for a new file (what Spotless expects of a checkout). */
export function eolOf(text) {
  return text.includes('\r\n') ? '\r\n' : text.includes('\n') ? '\n' : os.EOL;
}

export function readText(root, relative) {
  const file = resolve(root, relative);
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

export class Plan {
  constructor(root, { eol = os.EOL } = {}) {
    this.root = root;
    this.eol = eol;
    this.steps = [];
    this.conflicts = [];
    this.pending = new Map();
    this.observed = new Map();
  }

  /** The text a file will have once the plan is written: a planned write, else what is on disk. */
  current(relative) {
    if (this.pending.has(relative)) return this.pending.get(relative).text;
    const text = readText(this.root, relative);
    if (!this.observed.has(relative)) this.observed.set(relative, text);
    return text === null ? null : normalize(text);
  }

  /**
   * A new file. The same content already there is no change; other content is kept as it is, unless `owns` says the
   * file is not the one this command made (then the plan has a conflict and writes nothing).
   */
  create(relative, content, what, owns = () => true) {
    const existing = this.current(relative);
    const wanted = normalize(content);
    if (existing === null) {
      this.write(relative, wanted, `create ${relative}${what ? ` (${what})` : ''}`, this.eol);
    } else if (existing.trimEnd() === wanted.trimEnd()) {
      this.steps.push({ kind: 'unchanged', path: relative, what: `unchanged ${relative}` });
    } else if (owns(existing)) {
      this.steps.push({ kind: 'kept', path: relative, what: `kept ${relative} (changed since it was generated)` });
    } else {
      this.conflicts.push(`${relative} exists and is not ${what ?? 'the file this command writes'}`);
    }
  }

  /** A change of an existing file by a function of its text; no change when the function returns it unchanged. */
  patch(relative, change, what) {
    const existing = this.current(relative);
    if (existing === null) {
      this.conflicts.push(`${relative} is missing`);
      return;
    }
    const changed = change(existing);
    if (changed === existing) {
      this.steps.push({ kind: 'unchanged', path: relative, what: `unchanged ${relative} (${what})` });
      return;
    }
    const disk = readText(this.root, relative);
    this.write(relative, changed, `update ${relative} (${what})`, disk === null ? this.eol : eolOf(disk));
  }

  /** A step that runs after the writes (a script), shown in the plan. */
  after(what, run) {
    this.steps.push({ kind: 'run', what, run });
  }

  /** A line of advice printed after the plan. */
  note(text) {
    this.steps.push({ kind: 'note', what: text });
  }

  write(relative, text, what, eol) {
    if (!this.observed.has(relative)) this.observed.set(relative, readText(this.root, relative));
    const existing = this.pending.get(relative);
    if (existing) {
      existing.text = text;
      existing.what += `; ${what.replace(/^(create|update) \S+ ?/, '')}`;
      return;
    }
    const step = { kind: 'write', path: relative, text, what, eol };
    this.pending.set(relative, step);
    this.steps.push(step);
  }

  get changes() {
    return this.steps.filter((step) => step.kind === 'write').length;
  }

  /** CAS the observed sources; synchronous write failures restore every earlier file. No crash-atomicity claim. */
  applyAtomic(log) {
    if (this.steps.some((step) => step.kind === 'run')) throw new CliError('Atomic plans cannot run external scripts');
    const written = [];
    for (const relative of this.observed.keys()) safePath(this.root, relative);
    const lockDir = safePath(this.root, '.local');
    fs.mkdirSync(lockDir, { recursive: true });
    const lock = path.join(lockDir, 'cms-form-delivery.lock');
    let handle;
    let keepLock = false;
    try { handle = fs.openSync(lock, 'wx'); }
    catch (error) {
      if (error.code === 'EEXIST') throw new CliError('Concurrent delivery or interrupted process: inspect .local/cms-form-delivery.lock before retry');
      throw error;
    }
    try {
      for (const [relative, expected] of this.observed) {
        if (readText(this.root, relative) !== expected) throw new CliError(`Source changed concurrently: ${relative}`);
      }
      for (const step of this.steps) {
        if (step.kind === 'note') continue;
        log(step.what);
        if (step.kind !== 'write') continue;
        const file = safePath(this.root, step.path);
        if (readText(this.root, step.path) !== this.observed.get(step.path)) {
          throw new CliError(`Source changed concurrently: ${step.path}`);
        }
        const before = fs.existsSync(file) ? fs.readFileSync(file) : null;
        fs.mkdirSync(path.dirname(file), { recursive: true });
        let text = step.text.endsWith('\n') ? step.text : `${step.text}\n`;
        if (step.eol === '\r\n') text = text.replace(/\n/g, '\r\n');
        written.push({ file, before, expected: Buffer.from(text, 'utf8') });
        fs.writeFileSync(file, text, 'utf8');
      }
    } catch (error) {
      const failures = [];
      for (const { file, before, expected } of written.reverse()) {
        try {
          safePath(this.root, file);
          const current = fs.existsSync(file) ? fs.readFileSync(file) : null;
          if (current === null && before === null) continue;
          if (current !== null && before !== null && current.equals(before)) continue;
          if (current === null || !current.equals(expected)) throw new CliError('changed after the write; preserving current content');
          if (before === null) fs.unlinkSync(file); else fs.writeFileSync(file, before);
        } catch (restore) { failures.push(`${path.relative(this.root, file)}: ${restore.message}`); }
      }
      if (failures.length) {
        keepLock = true;
        throw new CliError(`Delivery failed: ${error.message}. Rollback incomplete: ${failures.join('; ')}`);
      }
      throw error;
    } finally {
      fs.closeSync(handle);
      if (!keepLock) fs.unlinkSync(lock);
    }
    for (const step of this.steps.filter((item) => item.kind === 'note')) log(step.what);
    if (!this.changes) log('Nothing to change: the repository already has all of it.');
  }

  /** Writes every planned file, or nothing when the plan has a conflict; returns the lines to print. */
  apply({ dryRun = false, log = console.log, atomic = false } = {}) {
    if (this.conflicts.length) {
      throw new CliError(`Nothing was written:\n  ${this.conflicts.join('\n  ')}`);
    }
    if (atomic && !dryRun) return this.applyAtomic(log);
    for (const step of this.steps) {
      if (step.kind === 'note') continue;
      log(`${dryRun ? '[dry-run] ' : ''}${step.kind === 'run' ? `run ${step.what}` : step.what}`);
      if (dryRun) continue;
      if (step.kind === 'write') {
        const file = resolve(this.root, step.path);
        fs.mkdirSync(path.dirname(file), { recursive: true });
        let text = step.text.endsWith('\n') ? step.text : `${step.text}\n`;
        if (step.eol === '\r\n') text = text.replace(/\n/g, '\r\n');
        fs.writeFileSync(file, text, 'utf8');
      } else if (step.kind === 'run') {
        step.run();
      }
    }
    const notes = this.steps.filter((step) => step.kind === 'note');
    if (notes.length) {
      log('');
      log('Next:');
      notes.forEach((step, index) => log(`  ${index + 1}. ${step.what}`));
    }
    if (!this.changes && !dryRun) log('Nothing to change: the repository already has all of it.');
  }
}
