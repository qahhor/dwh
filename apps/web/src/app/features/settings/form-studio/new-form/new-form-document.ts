import type { FormulaDocument } from '../studio-formulas.api';
import type { SimpleFormDeclaration } from './new-form.models';

/**
 * What the formulas of a declaration's header read besides its fields (ADR-0043): the columns of its tables of lines
 * (`lines.<table>.<column>`), its number (`docNumber`) and its status (`docStatus`); null for a form that is no document.
 */
export function documentContext(declaration: SimpleFormDeclaration): FormulaDocument | null {
  const lines = declaration.lines ?? [];
  if (!lines.length && !declaration.workflow && !declaration.entity.numbering) return null;
  return {
    lines: lines.map((table) => ({
      key: table.key,
      fields: table.fields.map((column) => ({
        id: column.id,
        type: column.type,
        ...(column.target ? { target: column.target.kind } : {}),
      })),
    })),
    numbered: !!declaration.entity.numbering,
    statuses: !!declaration.workflow,
  };
}
