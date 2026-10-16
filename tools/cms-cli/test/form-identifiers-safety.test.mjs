import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { validateForm } from '../lib/form-contract.mjs';
const fixture = JSON.parse(fs.readFileSync(new URL('../../../apps/server/src/test/resources/studio/simple-form.json', import.meta.url), 'utf8'));
for (const id of ['password', 'oldpassword', 'passwd', 'secret', 'apikey', 'myprivatekey', 'accesstoken', 'refreshtoken']) {
  test(`refuses runtime-reserved field ${id} before writing generated files`, () => {
    const declaration = structuredClone(fixture);
    declaration.fields[0].id = id;
    assert.throws(() => validateForm(declaration), /field.id.*reserved identifier/u);
  });
}
