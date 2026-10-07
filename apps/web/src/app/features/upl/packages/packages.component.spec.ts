import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { UplSource } from '../upl.api';
import { UplPackageItem } from './packages.api';
import {
  UPL_PACKAGES_META,
  UPL_RECEIVED,
  UPL_SOURCE_ITEMS,
  createPackagesFixture,
  keysetPage,
  uplPackage,
} from '@testing/upl-packages';

// How a refusal is spread over the fields is pinned by packages-errors.spec, the card by its own spec.

const DEFAULT_QUERY = { sort: { field: 'uploadedAt', descending: true }, conditions: [], match: 'all' };

describe('PackagesComponent', () => {
  it('без права загрузки формы нет, источники не запрашиваются, пустой текст короткий', async () => {
    const { api, screen } = await createPackagesFixture({ rights: [], pages: [of(keysetPage([]))] });

    expect(screen.testId('upl-pkg-form')).toHaveLength(0);
    expect(api.searchSources).not.toHaveBeenCalled();
    expect(screen.text()).toContain(PACKAGED_RUSSIAN['upl.pkg.empty']);
    expect(screen.text()).not.toContain(PACKAGED_RUSSIAN['upl.pkg.empty_hint']);
  });

  it('загрузка: источник ищется при открытии, шаблон его версии, кнопка оживает с четырьмя полями, отправка', async () => {
    const sent = new Subject<UplPackageItem>();
    const { api, toast, component, screen } = await createPackagesFixture({
      pages: [of(keysetPage([]))],
      uploads: [sent],
    });
    expect(screen.text()).toContain(PACKAGED_RUSSIAN['upl.pkg.empty_hint']);
    expect(api.searchSources).not.toHaveBeenCalled();
    TestBed.tick(); // smt-control points its label at the field after render
    const label = screen.all('label[for="upl-pkg-source-field"]')[0] as HTMLLabelElement;
    expect(document.getElementById(label.htmlFor)?.getAttribute('role')).toBe('combobox');

    const options = screen.openSources();
    expect(api.searchSources).toHaveBeenCalledWith('', null, 20);
    expect(options[0].textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.form.source_placeholder']);
    const column = (key: string, value: string) => `${PACKAGED_RUSSIAN[key]}: ${value}`;
    expect(options[1].getAttribute('aria-label')).toBe(
      `Nalogi TEST, ${column('upl.list.col.code', 'cement.output')}, ${column('upl.list.col.periodicity', PACKAGED_RUSSIAN['upl.periodicity.month'])}, ${column('upl.list.col.published_version', '2')}`,
    );
    expect(screen.testId('upl-pkg-template')).toHaveLength(0);
    screen.fillForm(false);
    const link = screen.testId('upl-pkg-template')[0] as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/api/v1/upl/sources/3/format-versions/2/template?lang=ru');
    expect(link.hasAttribute('download')).toBe(true);
    expect(link.textContent).toContain('анкета версии 2');
    expect(screen.submit().disabled).toBe(true);
    screen.attachFile();
    expect(screen.submit().disabled).toBe(false);

    screen.click('upl-pkg-submit');
    // While the file is on its way, nothing in the form can change.
    const source = screen.testId('upl-pkg-source')[0].querySelector('button[role="combobox"]');
    const dates = [screen.dateField('upl-pkg-period-from'), screen.dateField('upl-pkg-period-to')];
    const locked = [screen.submit(), source, ...dates, screen.testId('upl-pkg-file')[0]] as HTMLInputElement[];
    expect(locked.map((control) => control.disabled)).toEqual([true, true, true, true, true]);
    sent.next(uplPackage());
    sent.complete();

    expect(api.upload.mock.calls[0][0]).toMatchObject({
      sourceId: 3,
      periodFrom: '2026-01-01',
      periodTo: '2026-01-31',
    });
    expect(api.upload.mock.calls[0][0].file.name).toBe('a_jan.xlsx');
    expect(toast.success).toHaveBeenCalledWith(PACKAGED_RUSSIAN['upl.pkg.toast.accepted']);
    // Only the file is cleared: the source and the period serve the next file.
    expect(component.form()).toEqual(expect.objectContaining({ file: null, sourceId: 3, periodFrom: '2026-01-01' }));
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('OnPush: форма перерисовывается сама, когда источник выбран из кода, а файл очищен после загрузки', async () => {
    const { fixture, component, screen } = await createPackagesFixture();
    screen.fillForm();
    expect(screen.submit().disabled).toBe(false);
    // No other signal may change: the options stay as they are, only the form signal does.
    vi.spyOn(component.sourceOptions, 'update').mockImplementation(() => undefined);
    const created = { ...UPL_SOURCE_ITEMS[1], id: 9, lastPublishedVersion: 4 } as unknown as UplSource;

    (component as unknown as { chooseSource(source: UplSource): void }).chooseSource(created);
    fixture.detectChanges();
    const template = screen.testId('upl-pkg-template')[0] as HTMLAnchorElement;
    expect(template.getAttribute('href')).toBe('/api/v1/upl/sources/9/format-versions/4/template?lang=ru');

    (component as unknown as { clearFile(): void }).clearFile();
    fixture.detectChanges();
    expect(screen.submit().disabled).toBe(true);
    expect((screen.testId('upl-pkg-file')[0] as HTMLInputElement).value).toBe('');
  });

  it('поиск на сервере по коду или названию; «создать из поля» только с правом и с набранным названием', async () => {
    vi.useFakeTimers();
    const typeInSearch = (screen: Awaited<ReturnType<typeof createPackagesFixture>>['screen'], value: string) => {
      screen.openSources();
      const search = document.querySelector('.smt-select__search-input') as HTMLInputElement;
      search.value = value;
      search.dispatchEvent(new Event('input'));
      vi.advanceTimersByTime(400);
      screen.redraw();
      return document.querySelector('[role="option"][id$="-create"]') as HTMLElement | null;
    };
    try {
      const without = await createPackagesFixture({
        sources: [throwError(() => ({ status: 503 })), of(keysetPage(UPL_SOURCE_ITEMS))],
      });
      // A failed search says so inside the list, and its retry asks again.
      without.screen.openSources();
      (document.querySelector('.smt-select__error[role="alert"] button') as HTMLButtonElement).click();
      without.fixture.detectChanges();
      expect(document.querySelector('.smt-select__error')).toBeNull();
      expect(document.querySelectorAll('[role="option"]')).toHaveLength(3);
      expect(typeInSearch(without.screen, 'cem')).toBeNull();
      expect(without.api.searchSources).toHaveBeenCalledTimes(3);
      expect(without.api.searchSources).toHaveBeenLastCalledWith('cem', null, 20);
      document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());

      const { screen, navigate } = await createPackagesFixture({ rights: ['upload', 'create'] });
      const create = typeInSearch(screen, 'Новый')!;
      expect(create.textContent).toContain('Создать «Новый»');
      create.click();
      expect(navigate).toHaveBeenCalledWith(['/upl/sources'], {
        queryParams: { create: 'Новый', returnTo: 'packages' },
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('источник, созданный из формы, приходит выбранным; ссылка из обзора данных открывает карточку', async () => {
    const created = await createPackagesFixture({ query: { source: '9' } });
    expect(created.api.source).toHaveBeenCalledWith('9');
    expect(created.component.form().sourceId).toBe(9);
    created.fixture.detectChanges();
    expect(created.screen.testId('upl-pkg-source')[0].textContent).toContain('Created TEST');

    const linked = await createPackagesFixture({ query: { open: 'pkg-42' } });
    expect(linked.api.get).toHaveBeenCalledWith('pkg-42');
    expect(linked.component.selected()?.id).toBe('pkg-42');
  });

  it('отказ показан у своих полей, все разом, а неизвестный код — полосой над формой вместе с кодом', async () => {
    const errors = [
      { field: 'sourceId', code: 'UPL_PKG_SOURCE_REQUIRED', message: 'source' },
      { field: 'periodTo', code: 'UPL_PKG_PERIOD_ORDER', message: 'period' },
      { field: 'file', code: 'UPL_PKG_FILE_NOT_XLSX', message: 'file' },
    ];
    const { screen } = await createPackagesFixture({
      uploads: [
        throwError(() => ({ title: 'error', status: 422, code: 'validation_error', detail: 'invalid', errors })),
        throwError(() => ({ title: 'error', status: 503, code: 'service_unavailable', detail: 'UPL_PKG_FROM_FUTURE' })),
      ],
    });
    screen.fillForm();

    screen.click('upl-pkg-submit');

    expect(screen.fieldError('upl-pkg-source-field')).toContain(PACKAGED_RUSSIAN['upl.err.UPL_PKG_SOURCE_REQUIRED']);
    expect(screen.fieldError('upl-pkg-period-to-field')).toContain(PACKAGED_RUSSIAN['upl.err.UPL_PKG_PERIOD_ORDER']);
    expect(screen.fieldError('upl-pkg-file-field')).toContain(PACKAGED_RUSSIAN['upl.err.UPL_PKG_FILE_NOT_XLSX']);
    expect(screen.testId('upl-pkg-err-form')).toHaveLength(0);

    screen.click('upl-pkg-submit');

    expect(screen.testId('upl-pkg-err-form')[0].textContent).toContain('UPL_PKG_FROM_FUTURE (service_unavailable)');
    expect(screen.fieldError('upl-pkg-source-field')).toBeUndefined();
  });

  it('колонки из метаданных, по умолчанию сначала новые; строка со всеми полями, у «получен» подсказка', async () => {
    const { api, screen } = await createPackagesFixture({
      pages: [of(keysetPage([uplPackage(), uplPackage({ ...UPL_RECEIVED, id: 'c' })]))],
    });

    expect(screen.all('[role="columnheader"] span.truncate').map((cell) => cell.textContent?.trim())).toEqual(
      ['uploaded_at', 'source', 'period', 'file', 'status', 'rows'].map(
        (key) => PACKAGED_RUSSIAN[`upl.pkg.col.${key}`],
      ),
    );
    expect(api.list).toHaveBeenCalledWith(50, null, DEFAULT_QUERY);
    expect(screen.testId('filter-trigger')).toHaveLength(1);
    const [verified, received] = screen.rows();
    for (const part of ['Nalogi TEST', '01.01.2026–31.01.2026', 'a_jan.xlsx', '120 / 117 / 3']) {
      expect(verified.textContent).toContain(part);
    }
    expect(verified.textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.status.verified']);
    expect(verified.querySelector('smt-badge')!.getAttribute('title')).toBeNull();
    const hint = PACKAGED_RUSSIAN['upl.pkg.status.received_hint'];
    expect(received.querySelector('smt-badge')!.getAttribute('title')).toBe(hint);
  });

  it('без метаданных списка — полоса с «Повторить»; повтор и «Обновить» запрашивают список', async () => {
    const { api, queryMeta, screen } = await createPackagesFixture({
      metas: [throwError(() => ({ status: 503 })), of(UPL_PACKAGES_META)],
    });
    expect(screen.testId('upl-pkg-load-error')[0].textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.load_error']);
    expect(api.list).not.toHaveBeenCalled();

    screen.click('upl-pkg-retry');
    expect(queryMeta.get).toHaveBeenCalledTimes(2);
    expect(screen.testId('upl-pkg-load-error')).toHaveLength(0);
    expect(screen.testId('upl-pkg-row')).toHaveLength(1);

    screen.click('upl-pkg-refresh');
    expect(queryMeta.get).toHaveBeenCalledTimes(2);
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('сбой списка: повтор именно этого запроса; следующая страница — курсором того же запроса', async () => {
    const second = uplPackage({ id: '6f1b0d1e-0000-4000-8000-000000000002', fileName: 'b_feb.xlsx' });
    const { fixture, api, screen } = await createPackagesFixture({
      pages: [
        throwError(() => ({ status: 503 })),
        of(keysetPage([uplPackage()], true, 'cursor-2')),
        of(keysetPage([second])),
      ],
    });

    const alert = screen.all('ui-server-table [role="alert"]')[0];
    expect(alert.textContent).toContain(PACKAGED_RUSSIAN['upl.pkg.load_error']);
    (alert.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(screen.testId('upl-pkg-row')).toHaveLength(1);

    screen.all('button[aria-label="Следующая страница"]')[0].click();
    fixture.detectChanges();
    expect(api.list).toHaveBeenLastCalledWith(50, 'cursor-2', DEFAULT_QUERY);
    expect(screen.rows().map((row) => row.textContent)).toEqual([expect.stringContaining('b_feb.xlsx')]);
  });

  it('карточка вместо формы и списка; «Обновить» и «Применить» перечитывают список; «К списку» возвращает', async () => {
    const withoutRight = await createPackagesFixture();
    withoutRight.screen.clickRow();
    expect(withoutRight.screen.card().componentInstance.canApply()).toBe(false);

    const other = uplPackage({ id: 'other', fileName: 'b_q1.xlsx' });
    const { fixture, api, screen } = await createPackagesFixture({
      rights: ['upload', 'apply'],
      pages: [of(keysetPage([uplPackage(UPL_RECEIVED)])), of(keysetPage([uplPackage()])), of(keysetPage([other]))],
    });
    screen.clickRow();
    expect(screen.card().componentInstance.canApply()).toBe(true);
    expect(screen.testId('upl-pkg-row')).toHaveLength(0);
    expect(screen.testId('upl-pkg-form')).toHaveLength(0);
    expect(screen.testId('upl-pkg-card-meta')[0].textContent).toContain('Nalogi TEST');
    expect(screen.testId('upl-pkg-checking')).toHaveLength(1);

    // The open card takes the fresh row of the reloaded list.
    screen.click('upl-pkg-card-refresh');
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(screen.testId('upl-pkg-checking')).toHaveLength(0);
    expect(screen.testId('upl-pkg-counters')).toHaveLength(1);

    // The answer of «Apply» shows at once; an upload gone from the reloaded page stays as it was.
    screen.card().triggerEventHandler('applied', uplPackage({ status: 'applied', loadId: 42, rawRows: 120 }));
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(3);
    expect(screen.card().componentInstance.item().status).toBe('applied');
    expect(screen.text()).toContain('a_jan.xlsx');
    expect(screen.text()).not.toContain('b_q1.xlsx');

    screen.click('upl-pkg-back');
    expect(screen.card()).toBeNull();
    expect(screen.testId('upl-pkg-row')).toHaveLength(1);
    expect(screen.testId('upl-pkg-form')).toHaveLength(1);
  });
});
