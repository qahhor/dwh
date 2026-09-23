import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { LanguageInfo } from '../../../core/models/i18n.models';
import { SettingsLanguagesPanelComponent } from './settings-languages-panel.component';

const language = (code: string, name: string, builtin = true, coverage = 100): LanguageInfo => ({
  code, name, builtin, active: true, revision: 1, translated: coverage, total: 100, coverage,
});

const LANGUAGES = [language('ru', 'Русский'), language('en', 'English'), language('kk', 'Қазақша', false, 64)];

async function render(currentLang = 'ru', languages = LANGUAGES) {
  await TestBed.configureTestingModule({ imports: [SettingsLanguagesPanelComponent] }).compileComponents();
  const fixture = TestBed.createComponent(SettingsLanguagesPanelComponent);
  fixture.componentInstance.languages = languages;
  fixture.componentInstance.currentLang = currentLang;
  fixture.componentInstance.canUpdateSystemSettings = true;
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture;
}

describe('SettingsLanguagesPanelComponent on the vendored table', () => {
  it('renders one row per language through smt-table', async () => {
    const el: HTMLElement = (await render()).nativeElement;

    expect(el.querySelector('smt-table')).not.toBeNull();
    expect(el.querySelector('table')).toBeNull();
    for (const code of ['ru', 'en', 'kk']) {
      expect(el.querySelector(`[data-testid="edit-language-${code}"]`)).not.toBeNull();
    }
  });

  it('translates column headers through the application catalogue', async () => {
    const text = (await render()).nativeElement.textContent as string;

    expect(text).toContain('Код');
    expect(text).toContain('Название языка');
  });

  it('marks the current language and offers a switch only for the others', async () => {
    const el: HTMLElement = (await render('en')).nativeElement;

    expect(el.querySelector('[data-testid="switch-language-en"]')).toBeNull();
    expect(el.querySelector('[data-testid="switch-language-ru"]')).not.toBeNull();
    expect(el.textContent).toContain('Текущий активный');
  });

  it('emits the language code when a row action is used', async () => {
    const fixture = await render();
    const opened: string[] = [];
    const switched: string[] = [];
    fixture.componentInstance.openLanguageEditor.subscribe(code => opened.push(code));
    fixture.componentInstance.switchLanguage.subscribe(code => switched.push(code));

    (fixture.nativeElement.querySelector('[data-testid="edit-language-kk"]') as HTMLButtonElement).click();
    (fixture.nativeElement.querySelector('[data-testid="switch-language-en"]') as HTMLButtonElement).click();

    expect(opened).toEqual(['kk']);
    expect(switched).toEqual(['en']);
  });

  it('shows the table empty state from the application catalogue', async () => {
    const el: HTMLElement = (await render('ru', [])).nativeElement;

    expect(el.textContent).toContain('Ничего не найдено');
  });
});
