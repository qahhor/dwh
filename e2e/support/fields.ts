/**
 * The exact name of a form field as its label reads (docs/guidelines/forms-ux-standard.md, section 3). smt-control
 * draws the required mark "*" inside the label, hidden from assistive technology; Playwright's `getByLabel` with
 * `exact: true` reads the whole label text, mark included. This pattern matches the label with or without the mark,
 * and nothing longer: `page.getByLabel(fieldName('Пароль'))` finds "Пароль *" but not "Новый пароль *".
 */
export function fieldName(label: string): RegExp {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
  return new RegExp(`^\\s*${escaped}\\s*\\*?\\s*$`, 'u');
}
