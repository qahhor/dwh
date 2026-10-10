import { describe, expect, it } from 'vitest';
import { fieldFromPointer, problemFieldErrors } from './problem-fields';

describe('problemFieldErrors', () => {
  it('reads errors[] and invalid_params[] by field, the first message of a field winning', () => {
    const result = problemFieldErrors({
      status: 422,
      errors: [
        { field: 'code', code: 'required', message: 'Укажите код' },
        { field: 'code', code: 'pattern', message: 'Неверный формат' },
        { field: 'attributes.color', code: 'invalid', message: 'Неверный цвет' },
      ],
      invalid_params: [{ name: 'slaDays', reason: 'Не больше 366' }],
    });
    expect(result.fields).toEqual({
      code: 'Укажите код',
      'attributes.color': 'Неверный цвет',
      slaDays: 'Не больше 366',
    });
    expect(result.other).toEqual([]);
  });

  it('reads a JSON pointer as a field path', () => {
    expect(fieldFromPointer('/lines/2/qty')).toBe('lines[2].qty');
    expect(fieldFromPointer('/a~1b/c~0d')).toBe('a/b.c~d');
    expect(fieldFromPointer('name')).toBe('name');
    expect(problemFieldErrors({ errors: [{ pointer: '/name', message: 'Занято' }] }).fields).toEqual({
      name: 'Занято',
    });
  });

  it('renames server fields and sends the ones the form does not draw to other', () => {
    const result = problemFieldErrors(
      {
        errors: [
          { field: 'ownerOrg', message: 'Укажите организацию' },
          { field: 'tenant', message: 'Чужой тенант' },
        ],
      },
      { known: ['owner', 'code'], rename: { ownerOrg: 'owner' } },
    );
    expect(result.fields).toEqual({ owner: 'Укажите организацию' });
    expect(result.other).toEqual(['Чужой тенант']);
  });

  it('gives nothing for a problem without field errors or for anything else', () => {
    expect(problemFieldErrors({ status: 409, detail: 'Конфликт' })).toEqual({ fields: {}, other: [] });
    expect(problemFieldErrors(null)).toEqual({ fields: {}, other: [] });
    expect(problemFieldErrors('boom')).toEqual({ fields: {}, other: [] });
    expect(problemFieldErrors({ errors: [{ field: 3, message: 'x' }, null, { field: 'a' }] }).fields).toEqual({});
  });
});
