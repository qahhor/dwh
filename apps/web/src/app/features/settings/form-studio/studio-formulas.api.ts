import { inject, Injectable } from '@angular/core';
import type { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';

/** Where a formula stands in a form (ADR-0042). */
export type FormulaKind = 'formula' | 'defaultFormula' | 'visibleIf' | 'requiredIf' | 'readonlyIf' | 'rule';

/** A shown field of the form as a formula reads it. */
export interface FormulaCheckField {
  id: string;
  type: string;
  target?: 'list' | 'form';
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
  ): Observable<FormulaCheck> {
    const body: Record<string, unknown> = { fields, formula, kind };
    if (field) body['field'] = field;
    return this.api.post<FormulaCheck>('/studio/formulas/check', body, { notifyError: false });
  }
}
