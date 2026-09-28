import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UplFormatDraftRequest } from '../upl-api';
import { FormatFileStepComponent } from './format-file-step.component';
import { emptyModel } from './upl-format-model';

function render(model: UplFormatDraftRequest, editable = true) {
  TestBed.configureTestingModule({ imports: [FormatFileStepComponent] });
  const fixture = TestBed.createComponent(FormatFileStepComponent);
  fixture.componentRef.setInput('model', model);
  fixture.componentRef.setInput('editable', editable);
  fixture.detectChanges();
  return fixture;
}

const el = (fixture: ComponentFixture<FormatFileStepComponent>) => fixture.nativeElement as HTMLElement;
const trigger = (fixture: ComponentFixture<FormatFileStepComponent>, id: string) =>
  el(fixture).querySelector(`#${id}`) as HTMLButtonElement | null;
const shown = (fixture: ComponentFixture<FormatFileStepComponent>, id: string) =>
  trigger(fixture, id)?.querySelector('.smt-select__value')?.textContent?.trim();

/** Opens the smt-select behind the trigger id and picks the option with the label. */
function choose(fixture: ComponentFixture<FormatFileStepComponent>, id: string, label: string) {
  trigger(fixture, id)?.click();
  fixture.detectChanges();
  const option = [...document.querySelectorAll<HTMLElement>('.smt-select__option')].find(
    (item) => item.querySelector('.smt-select__option-label')?.textContent?.trim() === label,
  );
  if (!option) throw new Error(`no option "${label}" in ${id}`);
  option.click();
  fixture.detectChanges();
}

describe('FormatFileStepComponent', () => {
  it('shows the file kind and column matching the draft holds, without CSV settings for a workbook', () => {
    const fixture = render({ ...emptyModel(), matchColumnsBy: 'position' });

    expect(shown(fixture, 'upl-file-kind')).toBe('xlsx');
    expect(shown(fixture, 'upl-match-by')).toBe('По позиции');
    expect(trigger(fixture, 'upl-encoding')).toBeNull();
    expect(el(fixture).querySelector('#upl-delimiter')).toBeNull();
  });

  it('switching the draft to CSV fills in UTF-8 and a semicolon and shows both', () => {
    const model = emptyModel();
    const fixture = render(model);

    choose(fixture, 'upl-file-kind', 'csv');

    expect(model).toEqual(expect.objectContaining({ fileKind: 'csv', encoding: 'utf-8', delimiter: ';' }));
    expect(shown(fixture, 'upl-encoding')).toBe('utf-8');
    expect((el(fixture).querySelector('#upl-delimiter') as HTMLInputElement).value).toBe(';');
  });

  it('keeps the encoding and delimiter the draft already had when it becomes CSV again', () => {
    const model: UplFormatDraftRequest = { ...emptyModel(), encoding: 'windows-1251', delimiter: ',' };
    const fixture = render(model);

    choose(fixture, 'upl-file-kind', 'csv');

    expect(model).toEqual(expect.objectContaining({ fileKind: 'csv', encoding: 'windows-1251', delimiter: ',' }));
  });

  it('writes the chosen encoding, the typed delimiter and the column matching into the draft', () => {
    const model: UplFormatDraftRequest = { ...emptyModel(), fileKind: 'csv', encoding: 'utf-8', delimiter: ';' };
    const fixture = render(model);
    const delimiter = el(fixture).querySelector('#upl-delimiter') as HTMLInputElement;

    choose(fixture, 'upl-encoding', 'windows-1251');
    delimiter.value = '|';
    delimiter.dispatchEvent(new Event('input'));
    choose(fixture, 'upl-match-by', 'По позиции');

    expect(model).toEqual(
      expect.objectContaining({ encoding: 'windows-1251', delimiter: '|', matchColumnsBy: 'position' }),
    );
  });

  it('locks every field of a draft that may not be edited', () => {
    const fixture = render({ ...emptyModel(), fileKind: 'csv', encoding: 'utf-8', delimiter: ';' }, false);

    expect(trigger(fixture, 'upl-file-kind')?.disabled).toBe(true);
    expect(trigger(fixture, 'upl-encoding')?.disabled).toBe(true);
    expect(trigger(fixture, 'upl-match-by')?.disabled).toBe(true);
    expect((el(fixture).querySelector('#upl-delimiter') as HTMLInputElement).disabled).toBe(true);
  });
});
