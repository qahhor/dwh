import { readFileSync, readdirSync } from 'node:fs';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SMT_ICON_SYMBOLS, SMTIconComponent } from './icon';

const KIT_DIR = 'src/app/shared/ui-kit';
/** A literal kit key in a template: `key="checkbox-check"` or `[key]="'empty-state'"`. */
const KEY_LITERAL = /\bkey="([a-z0-9-]+)"|\[key\]="'([a-z0-9-]+)'"/g;

function render(key: string): string {
  const fixture = TestBed.createComponent(SMTIconComponent);
  fixture.componentRef.setInput('key', key);
  fixture.detectChanges();
  return (fixture.nativeElement as HTMLElement).textContent?.trim() ?? '';
}

describe('SMTIconComponent', () => {
  it('draws the checkbox marks as Material Symbols, not as the text of their kit keys', () => {
    expect(render('checkbox-check')).toBe('check');
    expect(render('checkbox-minus')).toBe('remove');
  });

  it('passes a Material Symbol name through unchanged', () => {
    expect(render('settings')).toBe('settings');
  });

  it('maps every hyphenated kit key the vendored templates bind', () => {
    const templates = readdirSync(KIT_DIR, { recursive: true, encoding: 'utf8' })
      .filter((name) => name.endsWith('.html') || (name.endsWith('.ts') && !name.endsWith('.spec.ts')))
      .map((name) => readFileSync(`${KIT_DIR}/${name}`, 'utf8'));
    const keys = new Set<string>();
    for (const text of templates) {
      for (const match of text.matchAll(KEY_LITERAL)) keys.add(match[1] ?? match[2]);
    }
    const unmapped = [...keys].filter((key) => key.includes('-') && !(key in SMT_ICON_SYMBOLS));

    expect(keys.has('checkbox-check')).toBe(true);
    expect(unmapped).toEqual([]);
  });
});
