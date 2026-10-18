import { describe, expect, it } from 'vitest';
import { incompatibleChanges, normalizedDeclaration, versionDraft } from '../forms/form-version.rules';
import { simpleFormPreview } from './new-form-preview';
import {
  editorDeclaration,
  placedLayout,
  titleEligible,
  titleOf,
  withFormat,
  type SimpleField,
  type SimpleFormDeclaration,
} from './new-form.models';

const labels = (text: string) => ({ ru: text, uz: text + ' uz', en: text + ' en' });

function field(id: string, change: Partial<SimpleField> = {}): SimpleField {
  return { id, type: 'text', labels: labels(id), required: true, listed: true, maxLength: 100, ...change };
}

function draft(change: Partial<SimpleFormDeclaration> = {}): SimpleFormDeclaration {
  return {
    formatVersion: 1,
    kind: 'simple-form',
    module: { code: 'studio', title: labels('Формы') },
    entity: { name: 'clients', title: labels('Клиенты') },
    fields: [field('title'), field('phone', { required: false }), field('old', { hidden: true })],
    ...change,
  };
}

describe('format 4 of a live form in the editor', () => {
  it('stays in an earlier format while the record is named by the system name and nothing is laid out', () => {
    const plain = withFormat({ ...draft(), entity: { ...draft().entity, titleField: 'name' } });
    expect(plain.formatVersion).toBe(1);
    expect(plain.entity.titleField).toBeUndefined();
    expect(titleOf(draft())).toBe('name');
  });

  it('writes the record number as no title field and a chosen field as the title, in format 4', () => {
    const numbered = withFormat({ ...draft(), entity: { ...draft().entity, titleField: 'recordNumber' } });
    expect(numbered.formatVersion).toBe(4);
    expect(numbered.entity.titleField).toBeUndefined();
    expect(titleOf(numbered)).toBe('recordNumber');
    expect(editorDeclaration(numbered).entity.titleField).toBe('recordNumber');
    const titled = withFormat({ ...draft(), entity: { ...draft().entity, titleField: 'title' } });
    expect(titled.entity.titleField).toBe('title');
    expect(titleEligible(draft().fields[0])).toBe(true);
    expect(draft().fields.slice(1).some(titleEligible)).toBe(false);
  });

  it('keeps the system name explicitly once a layout lifts a draft of an earlier format to format 4', () => {
    const laid = withFormat(
      draft({ layout: { sections: [{ key: 'main', labels: labels('Главное'), fields: ['phone'] }] } }),
    );
    expect(laid.formatVersion).toBe(4);
    expect(laid.entity.titleField).toBe('name');
    expect(laid.layout!.sections[0].fields).toEqual(['phone', 'title']);
  });

  it('places every shown field on one section and drops tabs fewer than two', () => {
    const layout = placedLayout(
      {
        tabs: [{ key: 'only', labels: labels('Одна') }],
        sections: [
          { key: 'a', labels: labels('A'), tab: 'only', fields: ['old', 'phone', 'gone'] },
          { key: 'b', labels: labels('B'), tab: 'only', fields: ['phone'] },
        ],
      },
      draft().fields,
    );
    expect(layout).toEqual({
      sections: [
        { key: 'a', labels: labels('A'), fields: ['phone', 'title'] },
        { key: 'b', labels: labels('B'), fields: [] },
      ],
    });
    const tabbed = placedLayout(
      {
        tabs: [
          { key: 'one', labels: labels('1') },
          { key: 'two', labels: labels('2') },
        ],
        sections: [{ key: 'a', labels: labels('A'), tab: 'nowhere', fields: [] }],
      },
      draft().fields,
    );
    expect(tabbed.sections[0].tab).toBe('one');
  });

  it('previews the title, the sections and the tabs as the form will draw them', () => {
    const source = draft({
      formatVersion: 4,
      entity: { ...draft().entity, titleField: 'title' },
      layout: {
        tabs: [
          { key: 'main', labels: labels('Основное') },
          { key: 'more', labels: labels('Ещё') },
        ],
        sections: [
          { key: 'a', labels: labels('Общее'), tab: 'main', fields: ['title'] },
          { key: 'b', labels: labels('Связь'), tab: 'more', fields: ['phone'] },
        ],
      },
    });
    const meta = simpleFormPreview(source, 'en');
    expect(meta.titleField).toBe('title');
    expect(meta.fields.map((item) => item.key)).toEqual(['title', 'phone']);
    expect(meta.layout.map((section) => section.label)).toEqual(['Общее en', 'Связь en']);
    expect(meta.tabs!.map((tab) => [tab.key, tab.sections])).toEqual([
      ['main', ['a']],
      ['more', ['b']],
    ]);
    const numbered = simpleFormPreview(
      { ...source, layout: undefined, entity: { ...source.entity, titleField: undefined } },
      'ru',
    );
    expect(numbered.fields[0].key).toBe('recordNumber');
    expect(numbered.layout[0].fields[0]).toBe('recordNumber');
  });

  it('refuses in a version to drop or take on the system name, and lets the layout and the title change', () => {
    const active = draft({ formatVersion: 3 });
    const kept = versionDraft(active);
    expect(incompatibleChanges(active, normalizedDeclaration(kept))).toEqual([]);
    const dropped = { ...kept, entity: { ...kept.entity, titleField: 'recordNumber' } };
    expect(incompatibleChanges(active, normalizedDeclaration(dropped))).toEqual([{ field: 'name', change: 'removed' }]);
    const numbered = draft({ formatVersion: 4 });
    const named = { ...versionDraft(numbered), entity: { ...numbered.entity, titleField: 'name' } };
    expect(incompatibleChanges(numbered, normalizedDeclaration(named))).toEqual([
      { field: 'name', change: 'required' },
    ]);
    const retitled = { ...versionDraft(numbered), entity: { ...numbered.entity, titleField: 'title' } };
    expect(incompatibleChanges(numbered, normalizedDeclaration(retitled))).toEqual([]);
    expect(normalizedDeclaration(retitled).formatVersion).toBe(4);
  });
});
