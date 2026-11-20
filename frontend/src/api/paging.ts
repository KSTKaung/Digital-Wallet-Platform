// Paged list requests (doc 02 §1.5): `?page=0&size=20`, page from 0, size 1–100. The answer is
// `{items, page: {page, size, totalElements, totalPages}}`. The server checks the bounds.

/** The page size the lists ask for (the server's default, doc 02 §2.4 "MVP history"). */
export const DEFAULT_PAGE_SIZE = 20;

export interface PageQuery {
  /** 0-based page index. */
  page: number;
  size?: number;
}

/** "?page=0&size=20" */
export function pageQueryString({ page, size = DEFAULT_PAGE_SIZE }: PageQuery): string {
  return `?${new URLSearchParams({ page: String(page), size: String(size) }).toString()}`;
}
