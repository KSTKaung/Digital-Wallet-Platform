// Renders the real route table (layout, AuthProvider, pages) in a memory router.
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '../routes/router';

/** `path` may carry a query string ("/transfers?page=2"). */
export function renderRoute(path: string, state?: unknown) {
  const [pathname = '/', query] = path.split('?', 2);
  const search = query === undefined ? '' : `?${query}`;
  const router = createMemoryRouter(routes, { initialEntries: [{ pathname, search, state }] });
  render(<RouterProvider router={router} />);
  return router;
}
