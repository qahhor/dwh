import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { I18nService } from '@core/services/i18n.service';
import { LoginTopBarComponent } from './login-top-bar.component';

function render() {
  const i18n = TestBed.inject(I18nService);
  i18n.languages.update((languages) => [
    ...languages,
    { ...languages[0], code: 'en', name: 'English', builtin: false },
    { ...languages[0], code: 'uz', name: 'Oʻzbekcha', builtin: false },
  ]);
  const setLanguage = vi.spyOn(i18n, 'setLanguage').mockReturnValue(of(undefined));
  const fixture = TestBed.createComponent(LoginTopBarComponent);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const trigger = () => host.querySelector('#login-language-select') as HTMLButtonElement;
  const open = () => {
    trigger().click();
    fixture.detectChanges();
    return [...document.querySelectorAll('.smt-select__option')] as HTMLElement[];
  };
  return { fixture, trigger, open, setLanguage };
}

describe('LoginTopBarComponent', () => {
  it('is a named language picker showing the language in use', () => {
    const { trigger } = render();

    expect(trigger().getAttribute('role')).toBe('combobox');
    expect(trigger().getAttribute('aria-label')).toContain('Язык интерфейса');
    expect(trigger().textContent).toContain('RU — Русский');
  });

  it('offers every language by its code and name', () => {
    const { open } = render();

    const options = open();
    expect(options.map((option) => option.querySelector('.smt-select__option-label')?.textContent?.trim())).toEqual([
      'RU — Русский',
      'EN — English',
      'UZ — Oʻzbekcha',
    ]);
    expect(options.map((option) => option.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
  });

  it('switches to the picked language for this visit only', () => {
    const { fixture, open, setLanguage } = render();

    open()[1].click();
    fixture.detectChanges();

    expect(setLanguage).toHaveBeenCalledWith('en', false);
  });

  it('does nothing when the language in use is picked again', () => {
    const { fixture, open, setLanguage } = render();

    open()[0].click();
    fixture.detectChanges();

    expect(setLanguage).not.toHaveBeenCalled();
  });
});
