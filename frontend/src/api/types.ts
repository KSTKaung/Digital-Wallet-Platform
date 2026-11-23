// Type helpers over the generated OpenAPI types. Types are looked up by PATH and METHOD. The
// committed document has stable operation ids since PR 7 (documentations/08 §4E E2), but the path
// is what the client calls, so it stays the key.

import type { components, paths } from './schema';

type Method = 'get' | 'put' | 'post' | 'delete' | 'patch';

type OperationOf<P extends keyof paths, M extends Method> = NonNullable<paths[P][M]>;

type ResponsesOf<P extends keyof paths, M extends Method> =
  OperationOf<P, M> extends { responses: infer R } ? R : never;

/** The 2xx statuses the document uses: 200, 201 (created), 202 (accepted), 204 (no content). */
type SuccessStatus = 200 | 201 | 202 | 204;

/** The success status(es) documented for `METHOD path`, e.g. 201 for a money POST. */
export type SuccessStatusOf<P extends keyof paths, M extends Method> = Extract<
  keyof ResponsesOf<P, M>,
  SuccessStatus
>;

/** JSON request body of `METHOD path`. */
export type RequestBodyOf<P extends keyof paths, M extends Method> =
  OperationOf<P, M> extends { requestBody: { content: { 'application/json': infer B } } }
    ? B
    : never;

/**
 * Response body of `METHOD path` for status S, by default the operation's documented success
 * status (so a 201 or 202 operation needs no explicit S). A status the operation does not document
 * is a compile error rather than a silent `never`; a success without a body (202, 204, an empty
 * 200) is `void`.
 */
export type ResponseBodyOf<
  P extends keyof paths,
  M extends Method,
  S extends SuccessStatusOf<P, M> = SuccessStatusOf<P, M>,
> = S extends keyof ResponsesOf<P, M>
  ? ResponsesOf<P, M>[S] extends { content: infer C }
    ? C[keyof C]
    : void
  : never;

export type Schemas = components['schemas'];
