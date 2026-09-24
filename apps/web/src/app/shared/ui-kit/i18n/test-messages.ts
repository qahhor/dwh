/* Test helper: fixed strings for specs that stub SMTI18nService. */
import { signal } from '@angular/core';
import type { SMTMessages } from './index';

export const TEST_MESSAGES: SMTMessages = {
  control: {
    required: 'This field is required',
    email: 'Enter a valid email address',
    pattern: 'Invalid format',
    parse: 'The value could not be read',
    invalid: 'Invalid value',
    minLength: count => `At least ${count} characters`,
    maxLength: count => `At most ${count} characters`,
    min: value => `Must be at least ${value}`,
    max: value => `Must be at most ${value}`,
  },
  common: { close: 'Close', cancel: 'Cancel' },
  table: { noResults: 'No results' },
  dataTable: { selectAll: 'Select all', selectRow: 'Select row' },
  tree: { expandAll: 'Expand all', collapseAll: 'Collapse all' },
  modalConfirm: { title: 'Confirmation', yes: 'Yes', no: 'No' },
};

export function testI18n() {
  return { messages: signal(TEST_MESSAGES) };
}
