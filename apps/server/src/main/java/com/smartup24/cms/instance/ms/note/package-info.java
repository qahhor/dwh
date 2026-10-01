/**
 * Notes ({@code ms} prefix: messaging and services): the reference entity of the low-code platform, declared through
 * {@code EntityDefinition} with its list, form, history and export (ADR-0019). It owns the {@code ms_note*} tables,
 * publishes {@code ms_note_pub_notes} and guards its endpoints with the {@code notes} permission area (ADR-0028).
 * Module map: {@code docs/architecture/module-map.md}.
 */
package com.smartup24.cms.instance.ms.note;
