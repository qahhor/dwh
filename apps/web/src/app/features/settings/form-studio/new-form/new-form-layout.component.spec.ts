import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { NewFormLayoutComponent, type LayoutChange } from './new-form-layout.component';
import type { SimpleField, SimpleFormDeclaration } from './new-form.models';

const labels = (text: string) => ({ ru: text, uz: text, en: text });

function field(id: string, change: Partial<SimpleField> = {}): SimpleField {
  return { id, type: 'text', labels: labels('Поле ' + id), required: true, listed: true, maxLength: 100, ...change };
}

function declaration(change: Partial<SimpleFormDeclaration> = {}): SimpleFormDeclaration {
  return {
    formatVersion: 3,
    kind: 'simple-form',
    module: { code: 'studio', title: labels('Формы') },
    entity: { name: 'clients', title: labels('Клиенты') },
    fields: [
      field('title'),
      field('phone', { required: false }),
      field('count', { type: 'number', maxLength: null, scale: 0 }),
      field('old', { hidden: true }),
    ],
    ...change,
  };
}

describe('NewFormLayoutComponent', () => {
  async function render(source: SimpleFormDeclaration, named: boolean | null = null) {
    await TestBed.configureTestingModule({ imports: [NewFormLayoutComponent] }).compileComponents();
    const fixture = TestBed.createComponent(NewFormLayoutComponent);
    fixture.componentRef.setInput('declaration', source);
    fixture.componentRef.setInput('named', named);
    fixture.detectChanges();
    await fixture.whenStable();
    const changes: LayoutChange[] = [];
    vi.spyOn(fixture.componentInstance.changed, 'emit').mockImplementation((change) => {
      changes.push(change);
      fixture.componentRef.setInput('declaration', {
        ...fixture.componentInstance.declaration(),
        entity: { ...fixture.componentInstance.declaration().entity, titleField: change.titleField },
        layout: change.layout,
      });
      fixture.detectChanges();
    });
    return { fixture, component: fixture.componentInstance, root: fixture.nativeElement as HTMLElement, changes };
  }

  it('offers the system name, the number and every required listed shown text field as the title', async () => {
    const { component, changes } = await render(declaration());
    expect(component.titleOptions().map((option) => option.id)).toEqual(['name', 'recordNumber', 'title']);
    expect(component.title()).toBe('name');
    component.setTitle('title');
    component.setTitle('phone');
    expect(changes).toEqual([{ titleField: 'title', layout: undefined }]);
  });

  it('keeps the system name of a version whose form is named by it, and offers no name to one without it', async () => {
    const named = await render(declaration(), true);
    expect(named.component.titleOptions().map((option) => option.id)).toEqual(['name']);
    expect(
      named.root.querySelector('[data-testid="form-title-field"] button, [data-testid="form-title-field"]'),
    ).not.toBeNull();
    expect(named.root.textContent).toContain('сохраняет');
    TestBed.resetTestingModule();
    const numbered = await render(declaration({ formatVersion: 4 }), false);
    expect(numbered.component.title()).toBe('recordNumber');
    expect(numbered.component.titleOptions().map((option) => option.id)).toEqual(['recordNumber', 'title']);
  });

  it('lays the form out in sections of the shown fields and moves a field to another section', async () => {
    const { component, changes, root } = await render(declaration());
    component.toggleLayout(true);
    expect(changes.at(-1)!.layout!.sections).toEqual([
      { key: 'main', labels: expect.any(Object), fields: ['title', 'phone', 'count'] },
    ]);
    component.addSection();
    expect(component.layout()!.sections.map((section) => section.key)).toEqual(['main', 'section1']);
    component.placeField('phone', 'section1');
    expect(component.sectionOf('phone')).toBe('section1');
    expect(component.layout()!.sections[0].fields).toEqual(['title', 'count']);
    component.setSectionLabel(1, 'ru', 'Контакты');
    expect(component.sectionOptions()[1].label).toBe('Контакты');
    component.moveSection(1, -1);
    expect(component.layout()!.sections.map((section) => section.key)).toEqual(['section1', 'main']);
    expect(root.querySelectorAll('[data-testid^="form-section-"]').length).toBeGreaterThanOrEqual(2);
    component.removeSection(0);
    expect(component.layout()!.sections).toHaveLength(1);
    expect(component.layout()!.sections[0].fields).toEqual(['title', 'count', 'phone']);
    component.toggleLayout(false);
    expect(changes.at(-1)!.layout).toBeUndefined();
  });

  it('puts the sections on two to six tabs and moves a section to another tab', async () => {
    const { component } = await render(declaration());
    component.toggleLayout(true);
    component.addSection();
    component.toggleTabs(true);
    expect(component.layout()!.tabs!.map((tab) => tab.key)).toEqual(['tab1', 'tab2']);
    expect(component.layout()!.sections.every((section) => section.tab === 'tab1')).toBe(true);
    component.setSectionTab(1, 'tab2');
    component.setTabLabel(1, 'en', 'More');
    expect(component.layout()!.sections[1].tab).toBe('tab2');
    component.addTab();
    expect(component.tabOptions()).toHaveLength(3);
    component.removeTab(1);
    expect(component.layout()!.tabs!.map((tab) => tab.key)).toEqual(['tab1', 'tab3']);
    expect(component.layout()!.sections[1].tab).toBe('tab1');
    component.removeTab(0);
    component.removeTab(0);
    expect(component.layout()!.tabs).toHaveLength(2);
    component.toggleTabs(false);
    expect(component.layout()!.tabs).toBeUndefined();
    expect(component.layout()!.sections.some((section) => section.tab)).toBe(false);
  });

  it('changes nothing while it is disabled', async () => {
    const { fixture, component, changes } = await render(declaration());
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();
    component.toggleLayout(true);
    component.setTitle('title');
    expect(changes).toEqual([]);
  });
});
