export interface E2eCredentials {
  baseURL: string;
  login: string;
  password: string;
}

export interface E2eEnvironment {
  instance: E2eCredentials;
  controlPlane: E2eCredentials;
}

export function loadE2eEnv(options?: {
  envFilePath?: string;
  processEnv?: Record<string, string | undefined>;
}): Readonly<E2eEnvironment>;
