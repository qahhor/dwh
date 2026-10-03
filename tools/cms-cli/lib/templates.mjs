// Every file and fragment the CLI writes (plan 10/10, item 6.1): the Java of a module and an entity, the SQL of its
// migrations, its catalog keys and its rows in the repository's registries. The generated code follows the server
// rules of phase 3 (docs/guidelines/module-development-guide.md) and the formatting Spotless (palantir) expects.
import { PLATFORM } from './layout.mjs';
import { humanize, labelKey, snake } from './names.mjs';

const MAX_LINE = 120;
const FIELD_INDENT = ' '.repeat(12);
const CHAIN_INDENT = ' '.repeat(20);

/** SQL string literal content. */
export function sqlText(value) {
  return String(value).replace(/'/g, "''");
}

/** Java string literal content. */
export function javaText(value) {
  return String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

/** A Javadoc-safe English line. */
function doc(value) {
  return String(value).replace(/\*\//g, '').trim();
}

/** The two lines every migration starts with (MigrationFileRulesTest). */
const MIGRATION_HEADER = "set lock_timeout = '2s';\nset statement_timeout = '60s';";

/** A comment block of lines no longer than the limit, every line starting with `-- `. */
function sqlComment(text) {
  const lines = [];
  let line = '--';
  for (const word of text.split(/\s+/).filter(Boolean)) {
    if (line.length + 1 + word.length > MAX_LINE) {
      lines.push(line);
      line = '--';
    }
    line += ` ${word}`;
  }
  lines.push(line);
  return lines.join('\n');
}

// ---------------------------------------------------------------------------------------------------------------
// Module

export function packageInfo(module) {
  return `/**
 * Module {@code ${module.code}}: ${doc(module.title.en)}.
 *
 * <p>Its entities are declarations on the general entity runtime (ADR-0019, ADR-0032): {@code tools/cms-cli} writes
 * them with {@code cms entity new ${module.code} <entity>}, and the runtime serves their records at
 * {@code /api/v1/entities/${module.area}.<entity>}. Describe here what the module is for (plan 10/10, item 4.3).
 */
package ${module.package};
`;
}

/** The module manifest placeholder; plan 10/10, item 6.4 decides its final form and its check at start. */
export function moduleManifest(module) {
  return `${JSON.stringify(
    {
      code: module.code,
      version: '0.1.0',
      minPlatform: null,
      dependencies: [],
      area: module.area,
      tablePrefix: module.tablePrefix,
      icon: module.icon,
      title: module.title,
    },
    null,
    2,
  )}\n`;
}

export function moduleMapRow(module) {
  return (
    `| \`${module.code}\` | \`${module.package}\` | ${module.title.ru}: сущности на общем runtime ` +
    `([ADR-0032](../adr/ADR-0032-low-code-platform-v2.md), §6), созданы \`tools/cms-cli\`; опишите назначение модуля | ` +
    `\`${module.tablePrefix}_*\` | \`${module.area}\` | runtime \`/api/v1/entities/${module.area}.*\` |`
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Entity: Java

/** The fields every new entity starts with: a name, a code unique among active rows, and the change time. */
export function starterFields(entity) {
  return [
    {
      key: 'name',
      type: 'text',
      column: 'name',
      required: true,
      min: 1,
      max: 255,
      list: 'sortable().searchable()',
      label: { ru: 'Название', en: 'Name', uz: 'Nomi' },
    },
    {
      key: 'code',
      type: 'text',
      column: 'code',
      required: true,
      min: 1,
      max: 64,
      pattern: '[a-z0-9_-]+',
      sqlCheck: "code ~ '^[a-z0-9_-]{1,64}$'",
      list: 'sortable().searchable()',
      label: { ru: 'Код', en: 'Code', uz: 'Kod' },
    },
  ].map((field) => ({ ...field, labelKey: labelKey(entity, field.key) }));
}

export function entityDeclaration(module, entity, options) {
  const fields = starterFields(entity);
  const fieldLines = fields.flatMap((field) => fieldDeclaration(entity, field).lines);
  const rightsKey = (action) => `${entity.code}.rights.${action}`;
  return `package ${entity.servicePackage};

import static ${PLATFORM.field}.EntityFields.instant;
import static ${PLATFORM.field}.EntityFields.sortable;
import static ${PLATFORM.field}.EntityFields.text;

import ${PLATFORM.entity}.Entity;
import ${PLATFORM.entity}.EntityCapability;
import ${PLATFORM.entity}.EntityDefinition;
import ${PLATFORM.entity}.EntityDefinition.EntityMenu;
import ${PLATFORM.entity}.EntityScope;
import ${PLATFORM.field}.FieldSource.SystemColumn;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Entity {@code ${entity.code}}: ${doc(options.title.en).slice(0, 80)}.
 *
 * <p>Every field is declared once (ADR-0019, ADR-0032). The general runtime serves the records at
 * {@code /api/v1/entities/${entity.code}} with their scope, field rules, revision, archive, audit and events; the
 * form and the list are derived from the fields, and the general screen {@code ${entity.route}} shows them.
 *
 * <p>A new field goes here and into a migration of the table:
 * {@code cms entity add-field ${entity.code} <field> --type <type>}. What the declaration cannot say goes into
 * hooks (ADR-0032, 6.5).
 */
@Configuration
public class ${entity.entityClass} {

    /** The entity's code, its form (ADR-0028) and its list's. */
    public static final String CODE = "${entity.code}";

    public static final EntityDefinition DEFINITION = Entity.define(CODE, CODE)
            .table("${entity.table}", "${entity.alias}")
            // Every row for whoever holds the right (ADR-0032, 5.1): a personal or org-unit record declares
            // EntityScope.owner(...) or EntityScope.orgUnit(...).
            .scope(EntityScope.all())
            .rights(
                    "${module.code}",
                    "${rightsKey('form')}",
                    Map.of(
                            "view", "${rightsKey('view')}",
                            "create", "${rightsKey('create')}",
                            "update", "${rightsKey('update')}",
                            "delete", "${rightsKey('delete')}"))
            // No route of its own: the item leads to the general screen ${entity.route} (ADR-0032, 7.1).
            .menu(new EntityMenu("${entity.navKey}", "${javaText(module.icon)}", "workspace", 100, "${module.area}"))
${fieldLines.join('\n')}
            .field(instant("modifiedAt", "${labelKey(entity, 'modifiedAt')}")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable()))
${sectionLine(['name', 'code'])}
            .actions("create", "update")
            .archivable()
            .actions("delete")
            .defaultSort("modifiedAt", Entity.Sort.DESC)
            .capabilities(
                    EntityCapability.SAVED_VIEWS,
                    EntityCapability.EXPORT,
                    EntityCapability.HISTORY,
                    EntityCapability.BULK)
            .build();

    /** Named apart from the bean of this configuration class itself. */
    @Bean
    public EntityDefinition ${entity.beanMethod}() {
        return DEFINITION;
    }
}
`;
}

export function entityHooks(entity) {
  return `package ${entity.servicePackage};

import ${PLATFORM.hook}.EntityHooks;
import ${PLATFORM.hook}.EntitySave;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * What the ${entity.code} declaration cannot say (ADR-0032, 6.5): the runtime calls these hooks at fixed steps of every
 * save, after the field rules and before the write. The code is kept in lower case, as its unique index compares it.
 * Data of this module goes through a repository of the module, another module's through its service.
 */
@Component
public class ${entity.hooksClass} implements EntityHooks {

    @Override
    public String entity() {
        return ${entity.entityClass}.CODE;
    }

    @Override
    public void beforeSave(EntitySave save) {
        String code = save.values().text("code");
        if (code != null && save.changed("code")) {
            save.values().set("code", code.strip().toLowerCase(Locale.ROOT));
        }
    }
}
`;
}

export function contractTest(module, entity) {
  return `package ${module.package};

import ${PLATFORM.kit}.EntityContractTestKit;

/**
 * The ${entity.code} entity passes the entity contract (ADR-0032, 11; plan 10/10, item 6.2) on the general runtime
 * /api/v1/entities/${entity.code}. Values the kit cannot make up (a reference, a file, an enumeration item) go into
 * fixture(...).
 */
class ${entity.testClass} extends EntityContractTestKit {

    @Override
    protected String entity() {
        return "${entity.code}";
    }
}
`;
}

/** `.section("main", ...)` as palantir lays it out: one line, or the arguments on the next line, or one per line. */
export function sectionLine(keys, section = 'main', labelKeyOfSection = 'entity.section.main') {
  const args = [`"${section}"`, `"${labelKeyOfSection}"`, ...keys.map((key) => `"${key}"`)];
  return wrapCall(FIELD_INDENT, '.section', args);
}

function wrapCall(indent, name, args) {
  const single = `${indent}${name}(${args.join(', ')})`;
  if (single.length <= MAX_LINE) return single;
  const inner = ' '.repeat(indent.length + 8);
  const joined = `${inner}${args.join(', ')})`;
  if (joined.length <= MAX_LINE) return `${indent}${name}(\n${joined}`;
  return `${indent}${name}(\n${args.map((arg) => `${inner}${arg}`).join(',\n')})`;
}

// ---------------------------------------------------------------------------------------------------------------
// Fields: one table of types, used by the declaration, the DDL and the catalog keys.

/**
 * The field types `cms entity add-field` writes, with the factory of EntityFields, the column type and the length a
 * text kind gets by default. Types the kit cannot fill on its own (ref) need a fixture when required.
 */
export const FIELD_TYPES = {
  text: { factory: 'text', sql: 'text', max: 255, list: 'sortable().searchable()' },
  textarea: { factory: 'textarea', sql: 'text', max: 4000, list: null },
  markdown: { factory: 'markdown', sql: 'text', max: 100000, list: null },
  email: { factory: 'email', sql: 'text', list: 'sortable().searchable()' },
  phone: { factory: 'phone', sql: 'text', list: 'sortable().searchable()' },
  url: { factory: 'url', sql: 'text', list: 'sortable()' },
  number: { factory: 'number', sql: 'numeric', list: 'sortable()' },
  date: { factory: 'date', sql: 'date', list: 'sortable()' },
  datetime: { factory: 'instant', sql: 'timestamptz', list: 'sortable()' },
  time: { factory: 'time', sql: 'time', list: 'sortable()' },
  bool: { factory: 'bool', sql: 'boolean', list: 'sortable()' },
  select: { factory: 'select', sql: 'text', list: 'sortable()' },
  money: { factory: 'money', sql: 'numeric(19, 4)', list: 'sortable()' },
  ref: { factory: 'ref', sql: 'bigint', list: 'sortable()' },
};

/** The column a field key takes: `dueOn` -> `due_on`, a reference `owner` -> `owner_id`, money -> two columns. */
export function columnOf(type, key) {
  const column = snake(key);
  if (type === 'ref') return column.endsWith('_id') ? column : `${column}_id`;
  return column;
}

/** The constant that holds the options of a select field: `status` -> `STATUS_OPTIONS`. */
export function optionsConstant(field) {
  return `${snake(field.key).toUpperCase()}_OPTIONS`;
}

/** The option label prefix of a select field (ADR-0031): `<entity code>.<field>.`. */
export function optionPrefix(entity, field) {
  return `${entity.code}.${snake(field.key)}.`;
}

/**
 * The declaration of a field: the lines of its `.field(...)` call, and the static imports, imports and constants it
 * needs in the declaration file.
 */
export function fieldDeclaration(entity, field) {
  const type = FIELD_TYPES[field.type];
  const label = field.labelKey ?? labelKey(entity, field.key);
  const staticImports = [`${PLATFORM.field}.EntityFields.${type.factory}`];
  const imports = [];
  const constants = [];
  let head;
  const calls = [];
  switch (field.type) {
    case 'select':
      head = `select("${field.key}", "${label}", ${optionsConstant(field)}, "${optionPrefix(entity, field)}")`;
      imports.push('java.util.List');
      constants.push(
        `    public static final List<String> ${optionsConstant(field)} = List.of(${field.options
          .map((option) => `"${javaText(option)}"`)
          .join(', ')});`,
      );
      calls.push(`column("${field.column}")`);
      break;
    case 'money':
      head = `money("${field.key}", "${label}", ${field.currencies.map((c) => `"${c}"`).join(', ')})`;
      calls.push(`money("${field.column}_amount", "${field.column}_currency")`);
      break;
    case 'ref':
      head = `ref("${field.key}", "${label}")`;
      calls.push(`column("${field.column}")`, `target("${field.target}", "${field.targetLabel}")`);
      break;
    default:
      head = `${type.factory}("${field.key}", "${label}")`;
      calls.push(`column("${field.column}")`);
  }
  if (field.required) calls.push('required()');
  if (field.max != null || field.min != null) calls.push(`length(${field.min ?? 'null'}, ${field.max ?? 'null'})`);
  if (field.pattern) calls.push(`matching("${javaText(field.pattern)}")`);
  if (field.type === 'number') calls.push(`scale(${field.scale})`);
  if (field.list) {
    calls.push(`list(${field.list})`);
    staticImports.push(`${PLATFORM.field}.EntityFields.${field.list.split('(')[0]}`);
  }
  return { lines: chain(head, calls), staticImports, imports, constants };
}

function chain(head, calls) {
  if (calls.length <= 1) {
    const line = `${FIELD_INDENT}.field(${head}${calls.map((call) => `.${call}`).join('')})`;
    if (line.length <= MAX_LINE) return [line];
  }
  const lines = [`${FIELD_INDENT}.field(${head}`];
  calls.forEach((call, index) => lines.push(`${CHAIN_INDENT}.${call}${index === calls.length - 1 ? ')' : ''}`));
  return lines;
}

// ---------------------------------------------------------------------------------------------------------------
// Entity: SQL

/**
 * The column definitions and the statements after them (an index) of a field; `create` writes `not null` for a
 * required field, an added column stays nullable so a table with rows takes it (as the Java diff does).
 */
export function fieldColumns(entity, field, create) {
  const table = entity.table;
  const notNull = create && field.required && field.type !== 'bool' ? ' not null' : '';
  const check = (expression) => ` constraint ${table}_ck_${field.column} check (${expression})`;
  const index = (column) => `create index ${table}_${column}_idx on ${table} (${column});`;
  const col = field.column;
  switch (field.type) {
    case 'select':
      return {
        columns: [`${col} text${notNull}${check(`${col} in (${field.options.map((o) => `'${sqlText(o)}'`).join(', ')})`)}`],
        after: [],
      };
    case 'money':
      return {
        columns: [
          `${col}_amount numeric(19, 4)${notNull}`,
          `${col}_currency text${notNull} constraint ${table}_ck_${col}_currency check (${col}_currency ~ '^[A-Z]{3}$')`,
        ],
        after: [],
      };
    case 'ref':
      return {
        columns: [
          `${col} bigint${notNull} constraint ${table}_fk_${col.replace(/_id$/, '')} references ${field.targetTable} (id)`,
        ],
        after: [index(col)],
      };
    case 'number':
      return { columns: [`${col} numeric(19, ${field.scale})${notNull}`], after: [] };
    default: {
      const type = FIELD_TYPES[field.type];
      let definition = `${col} ${type.sql}${notNull}`;
      if (field.sqlCheck) {
        definition += check(field.sqlCheck);
      } else if (field.max != null) {
        definition += check(
          field.min ? `char_length(${col}) between ${field.min} and ${field.max}` : `char_length(${col}) <= ${field.max}`,
        );
      }
      return { columns: [definition], after: [] };
    }
  }
}

export function tableMigration(entity, fields) {
  const t = entity.table;
  const columns = [`id bigint generated always as identity constraint ${t}_pkey primary key`];
  const after = [];
  for (const field of fields) {
    const part = fieldColumns(entity, field, true);
    columns.push(...part.columns);
    after.push(...part.after);
  }
  columns.push(
    `attributes jsonb not null default '{}'::jsonb\n        constraint ${t}_ck_attributes check (jsonb_typeof(attributes) = 'object')`,
    'archived_at timestamptz',
    `archived_by bigint constraint ${t}_fk_archived_by references md_users (id)`,
    `created_by bigint not null constraint ${t}_fk_created_by references md_users (id)`,
    `modified_by bigint not null constraint ${t}_fk_modified_by references md_users (id)`,
    'created_at timestamptz not null default clock_timestamp()',
    'modified_at timestamptz not null default clock_timestamp()',
    'revision bigint not null default 1',
  );
  return `${MIGRATION_HEADER}
${sqlComment(
  `The table of the entity ${entity.code} (tools/cms-cli): names and types by ADR-0020 and ADR-0032, 14.1; a revision ` +
    'every change raises and If-Match names (ADR-0024); custom field values in attributes; the archive keeps who ' +
    'archived a row and when, and the unique code ignores archived rows (ADR-0032, 5.4). Every foreign key has its index.',
)}
create table ${t} (
    ${columns.join(',\n    ')}
);

create unique index ${t}_code_uq on ${t} (lower(code)) where archived_at is null;
${[...after, `create index ${t}_archived_by_idx on ${t} (archived_by);`, `create index ${t}_created_by_idx on ${t} (created_by);`, `create index ${t}_modified_by_idx on ${t} (modified_by);`].join('\n')}
`;
}

export function rightsMigration(module, entity, tableVersion, title) {
  const form = entity.form;
  return `${MIGRATION_HEADER}
${sqlComment(
  `The right of the entity ${entity.code} (ADR-0028) with its actions, the grants to the system roles and the module ` +
    `${module.area} in the module registry (tools/cms-cli). Seed data only: the table is created by ${tableVersion}.`,
)}
insert into md_forms (code, module, name) values
('${form}', '${module.code}', '${sqlText(title.ru)}')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
('${form}', 'view', 'View'),
('${form}', 'create', 'Create'),
('${form}', 'update', 'Edit'),
('${form}', 'delete', 'Delete')
on conflict (form_code, action) do nothing;

-- Administrators and managers get every action
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('chief_admin', 'admin', 'manager') and fa.form_code = '${form}'
on conflict do nothing;

-- The user and auditor roles only view; analyst (V110) gets a module's rights from an administrator
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('user', 'auditor') and fa.form_code = '${form}' and fa.action = 'view'
on conflict do nothing;

insert into md_installed_modules (code, name, description, version, icon, route, is_system, status, sort_order) values
('${module.area}', '${sqlText(module.title.ru)}', '${sqlText(module.description ?? module.title.ru)}', '1.0.0', '${sqlText(module.icon)}', '${entity.route}', false, 'ACTIVE', 100)
on conflict (code) do nothing;
`;
}

export function fieldMigration(entity, field) {
  const part = fieldColumns(entity, field, false);
  const note = field.required
    ? `\n${sqlComment(`${field.key} is required on the form; the column takes null for the rows the table already has.`)}`
    : '';
  const add = part.columns.map((column) => `    add column ${column}`).join(',\n');
  return `${MIGRATION_HEADER}
${sqlComment(`The field ${field.key} of the entity ${entity.code} (tools/cms-cli): a column by ADR-0020.`)}${note}
alter table ${entity.table}
${add};
${part.after.length ? `\n${part.after.join('\n')}\n` : ''}`;
}

export function diffMigration(statements) {
  return `${MIGRATION_HEADER}
${sqlComment(
  'What the entity declarations need of the schema (cms migration diff, plan 10/10, item 6.1): written from the ' +
    'declarations the application runs and the schema the migrations produce.',
)}
${statements.join('\n\n')}
`;
}

// ---------------------------------------------------------------------------------------------------------------
// Catalog keys (ADR-0031): ru is the source language, uz and en complete.

export function entityKeys(entity, title) {
  const keys = {
    [entity.navKey]: title,
    [`${entity.code}.rights.form`]: title,
    [`${entity.code}.rights.view`]: { ru: 'Просмотр', en: 'View', uz: 'Koʻrish' },
    [`${entity.code}.rights.create`]: { ru: 'Создание', en: 'Create', uz: 'Yaratish' },
    [`${entity.code}.rights.update`]: { ru: 'Редактирование', en: 'Edit', uz: 'Tahrirlash' },
    [`${entity.code}.rights.delete`]: { ru: 'Удаление', en: 'Delete', uz: 'Oʻchirish' },
    [labelKey(entity, 'modifiedAt')]: { ru: 'Изменено', en: 'Modified', uz: 'Oʻzgartirilgan' },
  };
  for (const field of starterFields(entity)) keys[field.labelKey] = field.label;
  return keys;
}

export function fieldKeys(entity, field) {
  const keys = { [field.labelKey ?? labelKey(entity, field.key)]: field.label };
  if (field.type === 'select') {
    for (const option of field.options) {
      const text = humanize(option.replace(/[^a-zA-Z0-9]+/g, '_'));
      keys[`${optionPrefix(entity, field)}${option}`] = { ru: text, en: text, uz: text };
    }
  }
  return keys;
}

