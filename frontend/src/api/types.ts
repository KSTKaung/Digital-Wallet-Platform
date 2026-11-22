// Type helpers over the generated OpenAPI types. Look types up by PATH and METHOD, never by
// operation id: springdoc's default ids (get_1, list_1, ...) change with scan order
// (documentations/08 §5, PR 7 note).

import type { components, paths } from './schema';

type Method = 'get' | 'put' | 'post' | 'delete' | 'patch';

type OperationOf<P extends keyof paths, M extends Method> = NonNullable<paths[P][M]>;

/** JSON request body of `METHOD path`. */
export type RequestBodyOf<P extends keyof paths, M extends Method> =
  OperationOf<P, M> extends { requestBody: { content: { 'application/json': infer B } } }
    ? B
    : never;

/** Response body of `METHOD path` for status S (springdoc documents every success as 200). */
export type ResponseBodyOf<P extends keyof paths, M extends Method, S extends number = 200> =
  OperationOf<P, M> extends { responses: infer R }
    ? S extends keyof R
      ? R[S] extends { content: infer C }
        ? C[keyof C]
        : void
      : never
    : never;

export type Schemas = components['schemas'];
