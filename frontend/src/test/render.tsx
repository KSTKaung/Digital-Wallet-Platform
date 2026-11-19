// Renders the real route table (layout, AuthProvider, pages) in a memory router.
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '../routes/router';

export function renderRoute(path: string, state?: unknown) {
  const router = createMemoryRouter(routes, { initialEntries: [{ pathname: path, state }] });
  render(<RouterProvider router={router} />);
  return router;
}
