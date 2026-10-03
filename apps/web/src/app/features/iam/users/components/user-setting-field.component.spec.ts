import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LanguageInfo } from '@core/models/i18n.models';
import { I18nService } from '@core/services/i18n.service';
import { formField } from '@testing/form-meta';
import { UserSettingFieldComponent } from './user-setting-field.component';

function language(code: string, name: string, active: boolean): LanguageInfo {
  return { code, name, active, builtin: true, revision: 1, translated: 1, total: 1, coverage: 100 };
}

/* The language or time zone of a user account as a choice (ADR-0032 7.2). */
describe('UserSettingFieldComponent', () => {
  beforeEach(() => {
    TestBed.inject(I18nService).languages.set([
      language('ru', 'Русский', true),
      language('uz', "O'zbekcha", true),
      language('en', 'English', false),
    ]);
  });

  function render(key: 'language' | 'timeZone', value: unknown, set = vi.fn()) {
    const fixture = TestBed.createComponent(UserSettingFieldComponent);
    fixture.componentRef.setInput('field', formField(key, 'text', { label: key === 'language' ? 'Язык' : 'Зона' }));
    fixture.componentRef.setInput('set', set);
    fixture.componentRef.setInput('value', value);
    fixture.detectChanges();
    return { fixture, field: fixture.componentInstance, set };
  }

  it('offers the active languages of the system', () => {
    const { field } = render('language', 'ru');

    expect(field.options()).toEqual([
      { id: 'ru', label: 'Русский (ru)' },
      { id: 'uz', label: "O'zbekcha (uz)" },
    ]);
    expect(field.text()).toBe('ru');
  });

  it('offers the usual time zones and keeps a stored zone that is not among them', () => {
    const usual = render('timeZone', 'Asia/Tashkent').field.options();
    expect(usual.map((option) => option.id)).toContain('Asia/Tashkent');
    expect(usual.some((option) => option.id === 'America/New_York')).toBe(false);

    const kept = render('timeZone', 'America/New_York').field.options();
    expect(kept.at(-1)).toEqual({ id: 'America/New_York', label: 'America/New_York' });
    expect(kept).toHaveLength(usual.length + 1);
  });

  it('reads a value that is not text as none, and passes a choice to the form', () => {
    const { field, set } = render('language', 42);
    expect(field.text()).toBeNull();

    field.choose('uz');
    field.choose(null);
    expect(set).toHaveBeenCalledTimes(1);
    expect(set).toHaveBeenCalledWith('uz');
  });

  it('labels the choice with the field label', () => {
    const { fixture } = render('timeZone', null);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Зона');
  });
});
