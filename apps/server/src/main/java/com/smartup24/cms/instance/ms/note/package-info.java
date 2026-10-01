/**
 * Notes ({@code ms} prefix: messaging and services): the reference entity of the low-code platform, one declaration
 * ({@code MsNoteEntity}) that the general entity runtime serves at {@code /api/v1/entities/ms.notes} (ADR-0032, 6;
 * plan 10/10, item 5.4) with its list, form, history, export, bulk actions, audit and events — no controller, service or
 * repository of its own. It owns the {@code ms_note*} tables, publishes {@code ms_note_pub_notes} and guards its records
 * with the {@code notes} permission area (ADR-0028). Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.ms.note;
