// The command line of the SmartupCMS developer CLI (plan 10/10, item 6.1): help, parsing and dispatch.
import { parse } from './args.mjs';
import { entityAddField, entityNew, moduleNew } from './commands.mjs';
import { CliError, findRoot } from './layout.mjs';
import { Plan } from './plan.mjs';
import { doctor, migrationDiff } from './toolchain.mjs';

const HELP = `cms - SmartupCMS developer CLI

Usage:
  cms module new <code> [--title <ru>] [--title-en <en>] [--title-uz <uz>] [--area <area>]
                        [--table-prefix <prefix>] [--external] [--dir <dir>] [--package <pkg>]
  cms entity new <module> <entity> [--title <ru>] [--title-en <en>] [--title-uz <uz>] [--hooks]
                                   [--icon <name>] [--version V<n>] [--no-sync]
  cms entity add-field <entity code> <field> --type <type> [--required] [--label <ru>] [--label-en <en>]
                       [--label-uz <uz>] [--max <n>] [--scale <n>] [--options a,b] [--currencies UZS,USD]
                       [--target <entity code>] [--target-label <field>] [--column <name>] [--section <key>]
                       [--no-list] [--version V<n>] [--no-sync]
  cms migration diff [--write <name>] [--version V<n>] [--no-libs]
  cms doctor

Types: text, textarea, markdown, email, phone, url, number, date, datetime, time, bool, select, money, ref.
Every command takes --root <repository> and --dry-run (show the plan, write nothing). A command plans every write
first and writes nothing when a file it would create exists with other content; running it again changes nothing.

Examples:
  cms module new inventory --title "Склад" --title-en Inventory --title-uz Ombor
  cms module new library --external --title "Библиотека" --title-en Library --title-uz Kutubxona
  cms entity new inventory items --title "Товары" --title-en Items --title-uz Tovarlar --icon package --hooks
  cms entity add-field inventory.items price --type money --currencies UZS,USD --label "Цена" --label-en Price
  cms migration diff --write inventory_items_sync
`;

/** Each command and its options; the docs contract checks the commands the docs name (plan 10/10, item 6.6). */
export const OPTIONS = {
  'module new': {
    title: { type: 'string' },
    'title-en': { type: 'string' },
    'title-uz': { type: 'string' },
    area: { type: 'string' },
    'table-prefix': { type: 'string' },
    external: { type: 'boolean' },
    dir: { type: 'string' },
    package: { type: 'string' },
    group: { type: 'string' },
  },
  'entity new': {
    title: { type: 'string' },
    'title-en': { type: 'string' },
    'title-uz': { type: 'string' },
    hooks: { type: 'boolean' },
    icon: { type: 'string' },
    version: { type: 'string' },
    'no-sync': { type: 'boolean' },
  },
  'entity add-field': {
    type: { type: 'string' },
    required: { type: 'boolean' },
    label: { type: 'string' },
    'label-en': { type: 'string' },
    'label-uz': { type: 'string' },
    max: { type: 'string' },
    scale: { type: 'string' },
    options: { type: 'string' },
    currencies: { type: 'string' },
    target: { type: 'string' },
    'target-label': { type: 'string' },
    column: { type: 'string' },
    section: { type: 'string' },
    'no-list': { type: 'boolean' },
    version: { type: 'string' },
    'no-sync': { type: 'boolean' },
  },
  'migration diff': {
    write: { type: 'string' },
    version: { type: 'string' },
    'no-libs': { type: 'boolean' },
  },
  doctor: {},
};

export function main(argv, log = console.log) {
  const [group, action] = argv;
  const command = OPTIONS[`${group} ${action}`] ? `${group} ${action}` : OPTIONS[group] ? group : null;
  if (!command || argv.includes('--help') || argv.includes('-h')) {
    log(HELP);
    return command || !group || group === 'help' || argv.includes('--help') || argv.includes('-h') ? 0 : 2;
  }
  const rest = argv.slice(command.split(' ').length);
  const { values, positionals } = parse(rest, OPTIONS[command]);
  const root = findRoot(values.root);
  if (command === 'doctor') return doctor(root, log) ? 0 : 1;
  const plan = new Plan(root);
  if (command === 'module new') moduleNew(plan, positionals[0], values);
  if (command === 'entity new') entityNew(plan, positionals[0], positionals[1], values);
  if (command === 'entity add-field') entityAddField(plan, positionals[0], positionals[1], values);
  if (command === 'migration diff') migrationDiff(plan, values, log);
  plan.apply({ dryRun: values['dry-run'], log });
  return 0;
}

/** Runs a command line and sets the exit code; a CliError is a message, anything else a bug with its stack. */
export function run(argv) {
  try {
    process.exitCode = main(argv);
  } catch (error) {
    if (!(error instanceof CliError)) throw error;
    console.error(`cms: ${error.message}`);
    process.exitCode = 1;
  }
}
