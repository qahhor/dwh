import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { CommandPaletteFooterComponent } from './command-palette-footer.component';

describe('CommandPaletteFooterComponent', () => {
  it('lists the palette keys with what each does, in the person’s language', () => {
    const fixture = TestBed.createComponent(CommandPaletteFooterComponent);
    fixture.detectChanges();
    const shortcuts = Array.from(fixture.nativeElement.querySelectorAll('.shortcut-item')) as HTMLElement[];

    expect(shortcuts.map((item) => Array.from(item.querySelectorAll('kbd')).map((key) => key.textContent))).toEqual([
      ['↑', '↓'],
      ['↵'],
      ['ESC'],
    ]);
    expect(shortcuts.map((item) => item.textContent?.replace(/[↑↓↵]|ESC/g, '').trim())).toEqual([
      PACKAGED_RUSSIAN['search.shortcuts.navigate'],
      PACKAGED_RUSSIAN['search.shortcuts.select'],
      PACKAGED_RUSSIAN['common.close'],
    ]);
  });
});
