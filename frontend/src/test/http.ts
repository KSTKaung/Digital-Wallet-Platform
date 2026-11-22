// Test helpers: fetch stubs routed by method and path, unsigned test JWTs and a fake
// navigator.locks that records calls and serialises callbacks per lock name.
import { vi } from 'vitest';

export function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

export function emptyResponse(status: number) {
  return new Response(null, { status });
}

export function errorResponse(status: number, errorCode: string, message = 'Refused') {
  return jsonResponse(status, { success: false, errorCode, message, correlationId: 'srv-1' });
}

export interface RecordedRequest {
  method: string;
  path: string;
  /** The query string with its "?", or "" (e.g. "?page=0&size=20"). */
  search: string;
  headers: Headers;
  body: string | undefined;
  credentials: RequestCredentials | undefined;
}

type Handler = (request: RecordedRequest) => Response | Promise<Response>;

/**
 * Stubs global fetch. `routes` maps "METHOD /path" to a handler or a queue of handlers (used in
 * order, the last one repeats). Unknown routes answer 404 so a stray call fails loudly.
 */
export function stubFetch(routes: Record<string, Handler | Handler[]>) {
  const calls: RecordedRequest[] = [];
  const counters = new Map<string, number>();
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://gateway.test');
    const request: RecordedRequest = {
      method: (init.method ?? 'GET').toUpperCase(),
      path: url.pathname,
      search: url.search,
      headers: new Headers(init.headers),
      body: typeof init.body === 'string' ? init.body : undefined,
      credentials: init.credentials,
    };
    calls.push(request);
    const key = `${request.method} ${request.path}`;
    const route = routes[key];
    if (!route) {
      return errorResponse(404, 'NOT_FOUND', `No stub for ${key}`);
    }
    const handlers = Array.isArray(route) ? route : [route];
    const index = counters.get(key) ?? 0;
    counters.set(key, index + 1);
    const handler = handlers[Math.min(index, handlers.length - 1)]!;
    return handler(request);
  });
  vi.stubGlobal('fetch', fetchMock);
  return {
    fetchMock,
    calls,
    callsTo: (method: string, path: string) =>
      calls.filter((c) => c.method === method && c.path === path),
  };
}

function base64url(value: string): string {
  const binary = Array.from(new TextEncoder().encode(value), (b) => String.fromCharCode(b)).join(
    '',
  );
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** An unsigned JWT-shaped token; the client never verifies signatures. */
export function makeJwt(claims: Record<string, unknown>): string {
  return `${base64url(JSON.stringify({ alg: 'none' }))}.${base64url(JSON.stringify(claims))}.sig`;
}

export function tokenResponse(token: string, expiresIn = 900) {
  return jsonResponse(200, { accessToken: token, expiresIn });
}

export interface FakeLocks {
  requests: { name: string; mode: string | undefined }[];
  restore: () => void;
}

/** Installs a fake navigator.locks (exclusive, FIFO per name). */
export function installFakeLocks(): FakeLocks {
  const requests: FakeLocks['requests'] = [];
  const tails = new Map<string, Promise<unknown>>();
  const fake = {
    request(name: string, options: { mode?: string }, callback: () => Promise<unknown>) {
      requests.push({ name, mode: options.mode });
      const previous = tails.get(name) ?? Promise.resolve();
      const run = previous.then(
        () => callback(),
        () => callback(),
      );
      tails.set(
        name,
        run.catch(() => undefined),
      );
      return run;
    },
  };
  Object.defineProperty(navigator, 'locks', { value: fake, configurable: true, writable: true });
  return {
    requests,
    restore: () => {
      Reflect.deleteProperty(navigator, 'locks');
    },
  };
}

/** Removes navigator.locks for the "no Web Locks" fallback. */
export function removeLocks(): () => void {
  const had = 'locks' in navigator;
  const original = had ? navigator.locks : undefined;
  Object.defineProperty(navigator, 'locks', {
    value: undefined,
    configurable: true,
    writable: true,
  });
  return () => {
    if (had) {
      Object.defineProperty(navigator, 'locks', {
        value: original,
        configurable: true,
        writable: true,
      });
    } else {
      Reflect.deleteProperty(navigator, 'locks');
    }
  };
}

/** A promise with its resolve function, to hold a stubbed response until the test releases it. */
export function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
