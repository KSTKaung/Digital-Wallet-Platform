/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Optional absolute API origin. Empty or unset means same origin (Vite proxy / nginx). */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
