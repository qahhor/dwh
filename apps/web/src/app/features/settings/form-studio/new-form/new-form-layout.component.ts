import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTCheckboxComponent } from '@shared/ui-kit/components/forms/checkbox';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import type { StudioLanguage } from '../form-studio.models';
import { NewFormLabelsComponent } from './new-form-labels.component';
import {
  MAX_SECTIONS,
  MAX_TABS,
  MIN_TABS,
  NUMBER_TITLE,
  placedLayout,
  SYSTEM_TITLE,
  titleEligible,
  titleOf,
  type SimpleFormDeclaration,
  type SimpleFormLayout,
} from './new-form.models';

/** What the layout editor changes: the title field and the layout of the declaration. */
export interface LayoutChange {
  titleField: string;
  layout: SimpleFormLayout | undefined;
}

/**
 * The record title and the layout of a live form (ADR-0041, format 4): what names a record — the system name and
 * code, the record's number or a required listed text field — and, when the administrator lays the form out,
 * sections with their names, each shown field on one of them, and optionally 2 to 6 tabs. In a new version the title
 * keeps or goes without the system name as the active version does (`named`); everything else changes freely. Every
 * control is a labelled native or kit control in reading order, so the editor works from the keyboard and on a phone.
 */
@Component({
  selector: 'app-new-form-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTCheckboxComponent,
    SMTControlComponent,
    SMTSelectComponent,
    NewFormLabelsComponent,
  ],
  templateUrl: './new-form-layout.component.html',
  styleUrl: './new-form-layout.component.css',
})
export class NewFormLayoutComponent {
  private readonly i18n = inject(I18nService);
  readonly declaration = input.required<SimpleFormDeclaration>();
  readonly disabled = input(false);
  /**
   * In a new version, whether the active version is named by the system name (true) or not (false); null for a new
   * form, which may choose either.
   */
  readonly named = input<boolean | null>(null);
  readonly changed = output<LayoutChange>();

  /**
   * The title and the layout as last chosen here: they follow the declaration, and a change is kept at once, so a
   * second change made before the page draws the first builds on it instead of on the drawn declaration.
   */
  readonly title = linkedSignal(() => titleOf(this.declaration()));
  readonly layout = linkedSignal<SimpleFormLayout | null>(() => this.declaration().layout ?? null);
  readonly titleOptions = computed(() => {
    this.i18n.currentLang();
    const language = this.language();
    const named = this.named();
    const options: { id: string; label: string }[] = [];
    if (named !== false) options.push({ id: SYSTEM_TITLE, label: this.i18n.translate('studio.layout.title_system') });
    if (named !== true) {
      options.push({ id: NUMBER_TITLE, label: this.i18n.translate('studio.layout.title_number') });
      for (const field of this.declaration().fields.filter(titleEligible)) {
        options.push({ id: field.id, label: field.labels[language] || field.id });
      }
    }
    return options;
  });
  readonly shown = computed(() => this.declaration().fields.filter((field) => !field.hidden));
  readonly sectionOptions = computed(() => {
    const language = this.language();
    return (this.layout()?.sections ?? []).map((section, index) => ({
      id: section.key,
      label: section.labels[language] || this.i18n.translate('studio.layout.section_n', { n: index + 1 }),
    }));
  });
  readonly tabOptions = computed(() => {
    const language = this.language();
    return (this.layout()?.tabs ?? []).map((tab, index) => ({
      id: tab.key,
      label: tab.labels[language] || this.i18n.translate('studio.layout.tab_n', { n: index + 1 }),
    }));
  });
  readonly maxSections = MAX_SECTIONS;
  readonly maxTabs = MAX_TABS;
  readonly minTabs = MIN_TABS;

  setTitle(value: unknown): void {
    if (typeof value !== 'string' || !this.titleOptions().some((option) => option.id === value)) return;
    this.emit(value, this.layout() ?? undefined);
  }

  /** Lays the form out in one section of every shown field, or drops the layout. */
  toggleLayout(on: boolean): void {
    // A repeated switch to the state the form is in changes nothing: the layout already made is kept.
    if (on === (this.layout() !== null)) return;
    if (!on) {
      this.emit(this.title(), undefined);
      return;
    }
    this.emit(
      this.title(),
      placedLayout({ sections: [{ key: 'main', labels: this.defaultLabels('section'), fields: [] }] }, this.shown()),
    );
  }

  /** Gives the form two tabs, every section on the first, or drops them. */
  toggleTabs(on: boolean): void {
    if (on === !!this.layout()?.tabs) return;
    this.update((layout) => {
      if (!on) {
        delete layout.tabs;
        layout.sections.forEach((section) => delete section.tab);
        return;
      }
      layout.tabs = [
        { key: this.freeKey('tab', []), labels: this.defaultLabels('tab') },
        { key: this.freeKey('tab', ['tab1']), labels: this.defaultLabels('tab', 2) },
      ];
      layout.sections.forEach((section) => (section.tab = layout.tabs![0].key));
    });
  }

  addSection(): void {
    this.update((layout) => {
      if (layout.sections.length >= MAX_SECTIONS) return;
      const key = this.freeKey(
        'section',
        layout.sections.map((section) => section.key),
      );
      layout.sections.push({
        key,
        labels: this.defaultLabels('section', layout.sections.length + 1),
        ...(layout.tabs ? { tab: layout.tabs[layout.tabs.length - 1].key } : {}),
        fields: [],
      });
    });
  }

  removeSection(index: number): void {
    this.update((layout) => {
      if (layout.sections.length <= 1) return;
      const [removed] = layout.sections.splice(index, 1);
      layout.sections[0].fields.push(...removed.fields);
    });
  }

  moveSection(index: number, direction: number): void {
    this.update((layout) => {
      const next = index + direction;
      if (next < 0 || next >= layout.sections.length) return;
      [layout.sections[index], layout.sections[next]] = [layout.sections[next], layout.sections[index]];
    });
  }

  setSectionLabel(index: number, language: StudioLanguage, value: string): void {
    this.update((layout) => {
      layout.sections[index].labels[language] = value;
    });
  }

  setSectionTab(index: number, value: unknown): void {
    this.update((layout) => {
      if (typeof value === 'string' && layout.tabs?.some((tab) => tab.key === value))
        layout.sections[index].tab = value;
    });
  }

  addTab(): void {
    this.update((layout) => {
      if (!layout.tabs || layout.tabs.length >= MAX_TABS) return;
      layout.tabs.push({
        key: this.freeKey(
          'tab',
          layout.tabs.map((tab) => tab.key),
        ),
        labels: this.defaultLabels('tab', layout.tabs.length + 1),
      });
    });
  }

  removeTab(index: number): void {
    this.update((layout) => {
      if (!layout.tabs || layout.tabs.length <= MIN_TABS) return;
      const [removed] = layout.tabs.splice(index, 1);
      layout.sections
        .filter((section) => section.tab === removed.key)
        .forEach((section) => (section.tab = layout.tabs![0].key));
    });
  }

  setTabLabel(index: number, language: StudioLanguage, value: string): void {
    this.update((layout) => {
      if (layout.tabs) layout.tabs[index].labels[language] = value;
    });
  }

  /** The section a shown field is on. */
  sectionOf(fieldId: string): string | null {
    return this.layout()?.sections.find((section) => section.fields.includes(fieldId))?.key ?? null;
  }

  /** Moves a field to another section, after the fields already there. */
  placeField(fieldId: string, value: unknown): void {
    this.update((layout) => {
      const target = layout.sections.find((section) => section.key === value);
      if (!target) return;
      layout.sections.forEach((section) => (section.fields = section.fields.filter((id) => id !== fieldId)));
      target.fields.push(fieldId);
    });
  }

  /** The words of a field: its label in the working language, else its id. */
  fieldName(fieldId: string): string {
    const field = this.declaration().fields.find((candidate) => candidate.id === fieldId);
    return field?.labels[this.language()] || fieldId;
  }

  private update(change: (layout: SimpleFormLayout) => void): void {
    const current = this.layout();
    if (!current || this.disabled()) return;
    const next = structuredClone(placedLayout(current, this.declaration().fields));
    change(next);
    this.emit(this.title(), next);
  }

  private emit(titleField: string, layout: SimpleFormLayout | undefined): void {
    if (this.disabled()) return;
    this.title.set(titleField);
    this.layout.set(layout ?? null);
    this.changed.emit({ titleField, layout });
  }

  private freeKey(prefix: string, taken: string[]): string {
    let index = 1;
    while (taken.includes(`${prefix}${index}`)) index++;
    return `${prefix}${index}`;
  }

  private defaultLabels(kind: 'section' | 'tab', n = 1): { ru: string; uz: string; en: string } {
    const key = kind === 'section' ? 'studio.layout.section_n' : 'studio.layout.tab_n';
    const word = (language: StudioLanguage) => this.i18n.getDictionary(language)[key]?.replace('{n}', String(n));
    return { ru: word('ru') ?? `${n}`, uz: word('uz') ?? `${n}`, en: word('en') ?? `${n}` };
  }

  private language(): StudioLanguage {
    const language = this.i18n.currentLang();
    return language === 'uz' || language === 'en' ? language : 'ru';
  }
}
