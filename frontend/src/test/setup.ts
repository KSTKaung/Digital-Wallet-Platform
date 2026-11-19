import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { clearSession } from '../auth/tokenStore';

afterEach(() => {
  cleanup();
  // The token store is module state; never let one test's session leak into the next.
  clearSession();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
