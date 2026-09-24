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
  dropzone: {
    drop: 'Drop files here or',
    choose: 'click to choose',
    rejectedType: name => `${name}: this file type is not accepted`,
    rejectedSize: (name, size) => `${name}: the file is larger than ${size}`,
  },
  select: {
    placeholder: 'Choose…',
    name: 'Choose a value',
    search: 'Search the options',
    clearSearch: 'Clear the search',
    clear: 'Clear the choice',
    none: 'None',
    noResults: 'Nothing found',
    loading: 'Loading…',
    loadFailed: 'Could not load the options',
    retry: 'Retry',
    loadMore: 'Load more',
    remove: name => `Remove ${name}`,
    add: 'Choose',
    addMore: '+ Add',
  },
  date: {
    placeholder: 'dd.mm.yyyy',
    openCalendar: 'Open calendar',
    dialogLabel: 'Choose a date',
    rangeDialogLabel: 'Choose a period',
    previousMonth: 'Previous month',
    nextMonth: 'Next month',
    time: 'Time',
    invalid: format => `Enter a date as ${format}`,
    apply: 'Apply',
    anyPeriod: 'Any period',
    clearPeriod: 'Clear the period',
    presets: 'Quick picks',
    preset: {
      today: 'Today',
      yesterday: 'Yesterday',
      last7: 'Last 7 days',
      last30: 'Last 30 days',
      thisMonth: 'This month',
      lastMonth: 'Last month',
      thisYear: 'This year',
    },
  },
  common: { close: 'Close', cancel: 'Cancel' },
  table: { noResults: 'No results' },
  dataTable: { selectAll: 'Select all', selectRow: 'Select row' },
  tree: { expandAll: 'Expand all', collapseAll: 'Collapse all' },
  modalConfirm: { title: 'Confirmation', yes: 'Yes', no: 'No' },
};

/** Stub with the Russian day-first date pattern and the en-GB locale for readable, day-first labels. */
export function testI18n(language = 'ru', locale = 'en-GB') {
  return { messages: signal(TEST_MESSAGES), language: signal(language), locale: signal(locale) };
}
