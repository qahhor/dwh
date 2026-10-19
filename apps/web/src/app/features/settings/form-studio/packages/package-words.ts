import type { SMTTagTone } from '@shared/ui-kit/components/tag/tag.component';
import type { SolutionImportItem, SolutionLabels } from './packages.api';

type Translate = (key: string, params?: Record<string, string | number>) => string;
type Language = 'ru' | 'uz' | 'en';

/** The tone of an item's outcome: what is created or changed stands out, a refusal is a danger. */
export function outcomeTone(outcome: SolutionImportItem['outcome']): SMTTagTone {
  switch (outcome) {
    case 'create':
      return 'success';
    case 'upgrade':
      return 'info';
    case 'refused':
      return 'danger';
    case 'skip':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** A name in the viewer's language, else in Russian. */
export function labelOf(labels: SolutionLabels | null | undefined, language: string): string {
  if (!labels) return '';
  return labels[language as Language] || labels.ru;
}

/**
 * The problems of a refused item as text: each catalog key with its values, and the part of the item it is about
 * when the server names one. A value that is itself a catalog key is translated too.
 */
export function problemLines(item: SolutionImportItem, translate: Translate): string[] {
  return item.problems.map((problem) => {
    const params: Record<string, string | number> = {};
    for (const [key, value] of Object.entries(problem.params ?? {})) {
      params[key] = typeof value === 'number' ? value : String(value);
    }
    const text = translate(problem.message, params);
    return problem.field ? `${problem.field}: ${text}` : text;
  });
}

/** The counts of an import as one line: "2 created · 1 changed · 3 unchanged". */
export function countsLine(
  counts: { create: number; upgrade: number; unchanged: number; skip: number; refused: number },
  translate: Translate,
): string {
  const parts: [number, string][] = [
    [counts.create, 'studio.packages.count.create'],
    [counts.upgrade, 'studio.packages.count.upgrade'],
    [counts.unchanged, 'studio.packages.count.unchanged'],
    [counts.skip, 'studio.packages.count.skip'],
    [counts.refused, 'studio.packages.count.refused'],
  ];
  return parts
    .filter(([n]) => n > 0)
    .map(([n, key]) => translate(key, { n }))
    .join(' · ');
}

/** Saves the text as a JSON file the browser downloads. */
export function downloadJson(name: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = name;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
