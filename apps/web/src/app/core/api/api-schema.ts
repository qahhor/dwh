import type { components } from './openapi';

/**
 * A schema of the API description generated from the server's controllers (plan 10/10, item 3.3): `npm run
 * api:types` regenerates `openapi.d.ts` from `docs/api/openapi.json`. Prefer these types to hand-written copies of
 * server payloads.
 */
export type ApiSchema<Name extends keyof components['schemas']> = components['schemas'][Name];
