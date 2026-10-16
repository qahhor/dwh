// Bounded JSON without duplicate members, lossy UTF-8 decoding, trailing values or external references.
import { CliError } from './layout.mjs';

export function strictJson(bytes, { maxBytes = 32768, maxDepth = 8, integersOnly = false } = {}) {
  if (bytes.length > maxBytes) throw new CliError(`JSON byte limit: ${maxBytes}`);
  let text;
  try { text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); }
  catch { throw new CliError('Invalid UTF-8 JSON'); }
  let at = 0;
  const fail = (reason) => { throw new CliError(`Invalid JSON: ${reason} at ${at}`); };
  const space = () => { while (at < text.length && /[\t\n\r ]/.test(text[at])) at++; };
  function string() {
    const start = at++;
    while (at < text.length) {
      const c = text[at++];
      if (c === '\\') at++;
      else if (c === '"') {
        try { return JSON.parse(text.slice(start, at)); }
        catch { fail('string'); }
      }
    }
    fail('unterminated string');
  }
  function value(depth) {
    space();
    if (text[at] === '{' || text[at] === '[') {
      if (depth > maxDepth) fail('depth limit');
      const object = text[at++] === '{';
      const end = object ? '}' : ']';
      const result = object ? Object.create(null) : [];
      const keys = new Set();
      space();
      if (text[at] === end) { at++; return result; }
      for (;;) {
        space();
        let key;
        if (object) {
          if (text[at] !== '"') fail('expected object key');
          key = string();
          if (keys.has(key)) fail('duplicate key');
          keys.add(key);
          space();
          if (text[at++] !== ':') fail('expected colon');
        }
        const item = value(depth + 1);
        if (object) result[key] = item; else result.push(item);
        space();
        if (text[at] === end) { at++; return result; }
        if (text[at++] !== ',') fail('expected comma');
      }
    }
    if (text[at] === '"') return string();
    const match = /^(?:null|true|false|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(at));
    if (!match) fail('expected value');
    if (integersOnly && /^[0-9-]/.test(match[0]) && /[.eE]/.test(match[0])) fail('expected integer token');
    at += match[0].length;
    return JSON.parse(match[0]);
  }
  const result = value(1);
  space();
  if (at !== text.length) fail('trailing content');
  return result;
}
