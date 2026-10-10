import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { LanguageInfo } from '@core/models/i18n.models';
import { SettingsLanguagesPanelComponent, parseDictionary } from './settings-languages-panel.component';
import { NewLanguage } from '../settings.models';

const language = (code: string, name: string, builtin = true, coverage = 100): LanguageInfo => ({
  code,
  name,
  builtin,
  active: true,
  revision: 1,
  translated: coverage,
  total: 100,
  coverage,
});

const LANGUAGES = [language('ru', 'Русский'), language('en', 'English'), language('kk', 'Қазақша', false, 64)];

async function render(currentLang = 'ru', languages = LANGUAGES) {
  await TestBed.configureTestingModule({ imports: [SettingsLanguagesPanelComponent] }).compileComponents();
  const fixture = TestBed.createComponent(SettingsLanguagesPanelComponent);
  fixture.componentRef.setInput('languages', languages);
  fixture.componentRef.setInput('currentLang', currentLang);
  fixture.componentRef.setInput('canUpdateSystemSettings', true);
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
    // Drawn with divs, still announced as a named table with one row per language plus the header.
    const table = el.querySelector('[role="table"]')!;
    expect(table.getAttribute('aria-label')).toBeTruthy();
    expect(table.getAttribute('aria-rowcount')).toBe('4');
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
    fixture.componentInstance.openLanguageEditor.subscribe((code) => opened.push(code));
    fixture.componentInstance.switchLanguage.subscribe((code) => switched.push(code));

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

describe('SettingsLanguagesPanelComponent: the "add language" dialog', () => {
  // Dialogs render into the CDK overlay on document.body; each test starts without the last one's.
  afterEach(() => document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove()));
  async function openDialog() {
    const fixture = await render();
    const saved: NewLanguage[] = [];
    let closes = 0;
    fixture.componentInstance.saveNewLanguage.subscribe((language) => saved.push(language));
    fixture.componentInstance.closeAddLangModal.subscribe(() => closes++);
    fixture.componentRef.setInput('isAddLangModalOpen', true);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const type = (id: string, value: string) => {
      const field = document.getElementById(id) as HTMLInputElement | HTMLTextAreaElement;
      field.value = value;
      field.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    };
    const submit = async () => {
      (document.getElementById('settings-add-language') as HTMLFormElement).requestSubmit();
      fixture.detectChanges();
      await fixture.whenStable();
    };
    return { fixture, saved, closes: () => closes, type, submit };
  }

  it('reads a dictionary only when it is an object of texts', () => {
    expect(parseDictionary('')).toEqual({});
    expect(parseDictionary('{"a":"b"}')).toEqual({ a: 'b' });
    expect(parseDictionary('{bad')).toBeNull();
    expect(parseDictionary('[1]')).toBeNull();
    expect(parseDictionary('{"a":1}')).toBeNull();
  });

  it('keeps "Save language" enabled, shows the errors on save under the fields and focuses the first', async () => {
    const { saved, type, submit } = await openDialog();

    const save = document.querySelector<HTMLButtonElement>(
      '[data-testid="add-language-actions"] [data-testid="form-submit"]',
    )!;
    expect(save.disabled).toBe(false);
    expect(save.textContent?.trim()).toBe(PACKAGED_RUSSIAN['settings.languages.save_language']);
    await submit();
    expect(saved).toEqual([]);
    expect(document.getElementById('new-lang-code')?.getAttribute('aria-invalid')).toBe('true');
    expect(document.getElementById('new-lang-name')?.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement?.id).toBe('new-lang-code');

    type('new-lang-code', 'Not a code');
    type('new-lang-name', 'Қазақша');
    type('new-lang-json', '{bad');
    await submit();
    expect(saved).toEqual([]);
    expect(document.body.textContent).toContain(PACKAGED_RUSSIAN['settings.validation.lang_code']);
    expect(document.body.textContent).toContain(PACKAGED_RUSSIAN['settings.languages.invalid_json_format']);

    type('new-lang-code', ' KK ');
    type('new-lang-json', '{"common.save":"Сақтау"}');
    await submit();
    expect(saved).toEqual([{ code: 'kk', name: 'Қазақша', dictionary: { 'common.save': 'Сақтау' } }]);
  });

  it('closes an empty dialog at once and asks before dropping typed values', async () => {
    const { closes, type } = await openDialog();
    const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(false));
    const cancel = () =>
      document
        .querySelector<HTMLButtonElement>('[data-testid="add-language-actions"] [data-testid="form-cancel"]')!
        .click();

    cancel();
    expect(confirm).not.toHaveBeenCalled();
    expect(closes()).toBe(1);

    type('new-lang-name', 'Қазақша');
    cancel();
    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({ destructive: true }));
    expect(closes()).toBe(1);
  });
});
