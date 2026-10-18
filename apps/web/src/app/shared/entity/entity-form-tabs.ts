import { computed, effect, linkedSignal, untracked, type Signal } from '@angular/core';
import type { FormMeta, FormProblems } from '@core/models/form-meta.models';
import type { SMTTabItem } from '../ui-kit/components/tab-bar';

/** A tab of sections the form is drawn in (ADR-0032 9.3, ADR-0041): its title and the drawn sections it shows. */
export interface EntityFormTab {
  key: string;
  title: string;
  sections: string[];
}

/** What the tabs read from their form: its metadata, the drawn sections with their fields, the problems. */
export interface EntityFormTabsSource {
  meta: Signal<FormMeta>;
  /** The sections the screen asked for; tabs only when it asked for none. */
  only: Signal<readonly string[]>;
  drawn: Signal<readonly { section: { key: string }; fields: readonly { meta: { key: string } }[] }[]>;
  problems: Signal<FormProblems>;
  submitted: Signal<boolean>;
  translate: (key: string) => string;
  language: () => string;
}

let nextTabsId = 0;

/**
 * The tabs of an entity's form, as its card shows them (ADR-0032 9.3, ADR-0041): two or more tabs of the drawn
 * sections, the first open; every section stays in the form, only the open tab's are shown, so a save checks every
 * field. Each tab counts its fields with a problem, and a save that finds problems only on other tabs opens the first of
 * them. A form with fewer tabs, or drawing chosen sections only, shows every section at once.
 */
export class EntityFormTabs {
  /** The ids of the tabs and their panel, unique on the page. */
  readonly id = `entity-form-${nextTabsId++}`;

  readonly tabs = computed<EntityFormTab[]>(() => {
    this.source.language();
    if (this.source.only().length > 0) return [];
    const drawn = new Set(this.source.drawn().map((section) => section.section.key));
    const tabs = (this.source.meta().tabs ?? [])
      .filter((tab) => tab.kind === 'sections')
      .map((tab) => ({
        key: tab.key,
        title: tab.label ?? this.source.translate(tab.labelKey),
        sections: (tab.sections ?? []).filter((key) => drawn.has(key)),
      }))
      .filter((tab) => tab.sections.length > 0);
    return tabs.length >= 2 ? tabs : [];
  });

  /** The tab open now; the first one of a form that gets other tabs. */
  readonly active = linkedSignal<string>(() => this.tabs()[0]?.key ?? '');

  /** The name of the tab bar: the entity's name. */
  readonly label = computed(() => {
    this.source.language();
    const key = this.source.meta().titleKey;
    return key ? this.source.translate(key) : this.source.meta().code;
  });

  /** The problems of the fields on each tab. */
  private readonly problems = computed(() => {
    const counts = new Map<string, number>();
    const problems = this.source.problems();
    for (const tab of this.tabs()) {
      const keys = this.source
        .drawn()
        .filter((section) => tab.sections.includes(section.section.key))
        .flatMap((section) => section.fields.map((field) => field.meta.key));
      counts.set(tab.key, new Set(keys.filter((key) => !!problems[key])).size);
    }
    return counts;
  });

  /** The tabs as the bar shows them, each with the number of its fields that have a problem. */
  readonly items = computed<SMTTabItem[]>(() =>
    this.tabs().map((tab) => {
      const count = this.problems().get(tab.key) ?? 0;
      return {
        value: tab.key,
        label: tab.title,
        id: this.tabId(tab.key),
        panelId: this.panelId(),
        ...(count > 0 ? { count, countTone: 'attention' as const } : {}),
      };
    }),
  );

  constructor(private readonly source: EntityFormTabsSource) {
    effect(() => {
      if (!source.submitted()) return;
      const counts = this.problems();
      untracked(() => {
        if (counts.get(this.active())) return;
        const first = this.tabs().find((tab) => counts.get(tab.key));
        if (first) this.active.set(first.key);
      });
    });
  }

  /** Whether a section is drawn on the tab open now; every section is on a form without tabs. */
  shown(key: string): boolean {
    const tabs = this.tabs();
    return tabs.length === 0 || (tabs.find((tab) => tab.key === this.active())?.sections.includes(key) ?? true);
  }

  tabId(key: string): string {
    return `${this.id}-tab-${key}`;
  }

  panelId(): string {
    return `${this.id}-panel`;
  }
}
