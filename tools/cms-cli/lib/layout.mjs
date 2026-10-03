// Where the CLI reads and writes in the repository, and the packages of the platform types the generated code
// imports (plan 10/10, item 6.1). Everything that depends on the layout of the repository lives here and in
// templates.mjs, so a move of the public types (plan 10/10, item 6.3) or a module manifest of its own (item 6.4) is
// an edit of these two files.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** The root package of the server's modules. */
export const ROOT_PACKAGE = 'com.smartup24.cms.instance';

/** Repository paths, relative to the root, always with forward slashes. */
export const PATHS = {
  serverPom: 'apps/server/pom.xml',
  serverJava: 'apps/server/src/main/java/com/smartup24/cms/instance',
  serverTest: 'apps/server/src/test/java/com/smartup24/cms/instance',
  migrations: 'apps/server/src/main/resources/db/migration',
  manifest: 'apps/server/src/test/resources/migration-manifest.sha256',
  catalogs: 'apps/server/src/main/resources/i18n',
  permissionAreas: 'apps/server/src/main/java/com/smartup24/cms/instance/md/pref/PermissionAreas.java',
  moduleBoundaries: 'apps/server/src/test/java/com/smartup24/cms/instance/architecture/ModuleBoundariesTest.java',
  moduleMap: 'docs/architecture/module-map.md',
  // Lists every table with an attributes column (plan 10/10, item 4.6): a new entity table joins it.
  schemaOrderTest: 'apps/server/src/test/java/com/smartup24/cms/instance/db/SchemaOrderTest.java',
  coverageFloors: 'apps/server/coverage-floors.csv',
  // The module manifest placeholder: code, version, platform and dependencies (plan 10/10, item 6.4 owns the format).
  moduleManifests: 'apps/server/src/main/resources/modules',
  webRoot: 'apps/web',
  syncRussian: 'apps/web/scripts/sync-packaged-russian.mjs',
  mavenWrapper: 'mvnw',
};

/** Packages of the platform types the generated Java imports (plan 10/10, item 6.3 may move them). */
export const PLATFORM = {
  entity: `${ROOT_PACKAGE}.common.entity`,
  field: `${ROOT_PACKAGE}.common.entity.field`,
  hook: `${ROOT_PACKAGE}.common.entity.hook`,
  kit: `${ROOT_PACKAGE}.support.entity`,
};

/** The catalogs every key goes into; ru is the source language (ADR-0031). */
export const LANGUAGES = ['ru', 'uz', 'en'];

/** The Java test class that compares declarations with the migrated schema (`cms migration diff`). */
export const SCHEMA_DIFF_TEST = 'EntitySchemaDiffTest';

/** The first coverage floor of a new module: the target of plan 10/10, item 1.4, raised after the first measure. */
export const NEW_MODULE_FLOOR = { line: 80, branch: 50 };

/** The repository root: `--root`, else the first parent of the working directory with the server's pom. */
export function findRoot(explicit) {
  const isRoot = (dir) =>
    fs.existsSync(path.join(dir, PATHS.serverPom)) && fs.existsSync(path.join(dir, PATHS.migrations));
  if (explicit) {
    const dir = path.resolve(explicit);
    if (!isRoot(dir)) throw new CliError(`Not a SmartupCMS repository: ${dir}`);
    return dir;
  }
  for (const start of [process.cwd(), path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')]) {
    let dir = path.resolve(start);
    for (;;) {
      if (isRoot(dir)) return dir;
      const parent = path.dirname(dir);
      if (parent === dir) break;
      dir = parent;
    }
  }
  throw new CliError('No SmartupCMS repository above the working directory; pass --root <dir>');
}

/** An absolute path of a repository path. */
export function resolve(root, relative) {
  return path.join(root, ...relative.split('/'));
}

/** An error the CLI reports as a message, without a stack. */
export class CliError extends Error {}
