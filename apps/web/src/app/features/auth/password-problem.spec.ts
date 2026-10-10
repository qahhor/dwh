import { describe, expect, it } from 'vitest';
import { passwordProblemFields, problemMessage } from './password-problem';

describe('passwordProblemFields', () => {
  it('takes the fields the server named first', () => {
    const problem = {
      code: 'validation_failed',
      errors: [
        { field: 'newPassword', code: 'Size', message: 'Слишком длинный' },
        { field: 'token', code: 'NotBlank', message: 'Нет ссылки' },
      ],
    };

    expect(passwordProblemFields(problem, ['newPassword'])).toEqual({
      fields: { newPassword: 'Слишком длинный' },
      other: ['Нет ссылки'],
    });
  });

  it('puts a policy refusal under the new password and a wrong current password under the old one', () => {
    expect(passwordProblemFields({ code: 'PASSWORD_POLICY', detail: 'Простой' }, ['newPassword']).fields).toEqual({
      newPassword: 'Простой',
    });
    expect(
      passwordProblemFields({ code: 'invalid_credentials', detail: 'Неверный' }, ['oldPassword', 'newPassword']).fields,
    ).toEqual({ oldPassword: 'Неверный' });
  });

  it('gives nothing for a refusal of no field, or of a field the form does not draw', () => {
    expect(passwordProblemFields({ code: 'rate_limited', detail: 'Позже' }, ['newPassword']).fields).toEqual({});
    expect(passwordProblemFields({ code: 'invalid_credentials', detail: 'Неверный' }, ['newPassword']).fields).toEqual(
      {},
    );
    expect(passwordProblemFields({ code: 'password_policy' }, ['newPassword']).fields).toEqual({});
    expect(passwordProblemFields(null, ['newPassword']).fields).toEqual({});
  });
});

describe('problemMessage', () => {
  it('prefers the server detail, then its message, then the fallback', () => {
    expect(problemMessage({ detail: 'Деталь', message: 'Сообщение' }, 'Запас')).toBe('Деталь');
    expect(problemMessage({ detail: ' ', message: 'Сообщение' }, 'Запас')).toBe('Сообщение');
    expect(problemMessage({}, 'Запас')).toBe('Запас');
    expect(problemMessage('text', 'Запас')).toBe('Запас');
  });
});
