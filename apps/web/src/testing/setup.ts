import { PACKAGED_RUSSIAN } from '../app/core/i18n/packaged-russian';

(globalThis as typeof globalThis & {
  __SMARTUPCMS_TEST_RUSSIAN__?: Readonly<Record<string, string>>;
}).__SMARTUPCMS_TEST_RUSSIAN__ = PACKAGED_RUSSIAN;
