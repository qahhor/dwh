import { inject, Injectable } from '@angular/core';
import type { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';

/** Where a formula stands in a form (ADR-0042). */
export type FormulaKind =
  'formula' | 'defaultFormula' | 'visibleIf' | 'requiredIf' | 'readonlyIf' | 'rule' | 'condition';

/** A shown field of the form as a formula reads it; a computed one is not read by a formula of a line (ADR-0043). */
export interface FormulaCheckField {
  id: string;
  type: string;
  target?: 'list' | 'form';
  computed?: boolean;
}

/**
 * What a formula of a document reads besides its fields (ADR-0043): the tables of lines and their columns, the table
 * whose column the formula computes (then it reads its row and `doc.<field>`), the number and the status.
 */
export interface FormulaDocument {
  lines: { key: string; fields: FormulaCheckField[] }[];
  line?: string | null;
  numbered: boolean;
  statuses: boolean;
}

/** `POST /api/v1/studio/formulas/check`: the formula's type, or the reason and the position of its first problem. */
export interface FormulaCheck {
  valid: boolean;
  type?: string | null;
  reason?: string | null;
  position?: number | null;
  name?: string | null;
  messageKey?: string | null;
}

/** The server's check of one formula while the editor types it: the same checker the activation runs. */
@Injectable({ providedIn: 'root' })
export class StudioFormulasApi {
  private readonly api = inject(ApiService);

  check(
    fields: readonly FormulaCheckField[],
    formula: string,
    kind: FormulaKind,
    field: string | null,
    document: FormulaDocument | null = null,
  ): Observable<FormulaCheck> {
    const body: Record<string, unknown> = { fields, formula, kind };
    if (field) body['field'] = field;
    if (document) {
      if (document.lines.length) body['lines'] = document.lines;
      if (document.line) body['line'] = document.line;
      if (document.numbered) body['numbered'] = true;
      if (document.statuses) body['statuses'] = true;
    }
    return this.api.post<FormulaCheck>('/studio/formulas/check', body, { notifyError: false });
  }
}
