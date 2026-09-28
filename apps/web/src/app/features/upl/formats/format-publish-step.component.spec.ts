import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UplFormatDraftRequest, UplFormatVersion } from '../upl-api';
import { FormatPublishStepComponent } from './format-publish-step.component';
import { emptyColumn, emptyModel, emptySheet } from './upl-format-model';

const version = (status: UplFormatVersion['status'], validFrom: string | null = null) =>
  ({ status, validFrom, sheets: [] }) as unknown as UplFormatVersion;

/** A draft of two sheets holding two columns and one column. */
function draft(): UplFormatDraftRequest {
  const first = { ...emptySheet(), sheetName: 'Продажи', columns: [emptyColumn(), emptyColumn()] };
  const second = { ...emptySheet(), sheetName: 'Остатки', columns: [emptyColumn()] };
  return { ...emptyModel(), fileKind: 'csv', sheets: [first, second] };
}

function render(model: UplFormatDraftRequest, inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({ imports: [FormatPublishStepComponent] });
  const fixture = TestBed.createComponent(FormatPublishStepComponent);
  fixture.componentRef.setInput('model', model);
  fixture.componentRef.setInput('version', version('draft'));
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return fixture;
}

const el = (fixture: ComponentFixture<FormatPublishStepComponent>) => fixture.nativeElement as HTMLElement;
const text = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
const byTestId = (fixture: ComponentFixture<FormatPublishStepComponent>, id: string) =>
  text(el(fixture).querySelector(`[data-testid="${id}"]`));
const review = (fixture: ComponentFixture<FormatPublishStepComponent>) =>
  [...el(fixture).querySelectorAll('[data-testid="upl-review"] > div')].map((row) => [
    text(row.querySelector('dt')),
    text(row.querySelector('dd')),
  ]);

describe('FormatPublishStepComponent', () => {
  it('sums up what the draft holds: status, file kind, sheets and columns', () => {
    const fixture = render(draft());

    expect(review(fixture)).toEqual([
      ['Статус', 'Черновик'],
      ['Вид файла', 'csv'],
      ['Листов', '2'],
      ['Колонок', '3'],
    ]);
    expect(byTestId(fixture, 'upl-review-state')).toBe(
      'Формат готов к публикации. Дата начала действия выбирается при публикации',
    );
  });

  it('shows the draft as it is when the step is shown again after another step edited it', () => {
    const model = draft();
    const fixture = render(model, { shown: false });

    model.sheets[1].columns.push(emptyColumn(), emptyColumn());
    model.sheets.push({ ...emptySheet(), columns: [emptyColumn()] });
    fixture.componentRef.setInput('shown', true);
    fixture.detectChanges();

    expect(byTestId(fixture, 'upl-review-sheets')).toBe('3');
    expect(byTestId(fixture, 'upl-review-columns')).toBe('6');
  });

  it('asks for a sheet with columns before a draft can be published', () => {
    const model = draft();
    model.sheets[1].columns = [];
    const fixture = render(model);

    expect(byTestId(fixture, 'upl-review-state')).toBe('Перед публикацией добавьте хотя бы один лист с колонками');
  });

  it('asks to fix the errors first, and warns that unsaved changes are saved on publishing', () => {
    const fixture = render(draft(), { errorCount: 2, dirty: true, previousValidFrom: '01.08.2026' });

    expect(byTestId(fixture, 'upl-review-state')).toBe('Перед публикацией исправьте ошибки на отмеченных шагах');
    expect(byTestId(fixture, 'upl-review-unsaved')).toBe(
      'Есть несохранённые изменения — они сохранятся перед публикацией',
    );
    expect(text(el(fixture))).toContain('Прежняя версия действует с 01.08.2026');
  });

  it('says a published version is fixed and shows the day it is valid from', () => {
    const fixture = render(draft(), { version: version('published', '2026-09-01'), dirty: true });

    expect(byTestId(fixture, 'upl-review-state')).toBe('Версия уже не черновик: её параметры зафиксированы');
    expect(review(fixture)).toContainEqual(['Действует с', '01.09.2026']);
    expect(el(fixture).querySelector('[data-testid="upl-review-unsaved"]')).toBeNull();
  });
});
