import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { errorResponse, jsonResponse, stubFetch } from '../test/http';
import { routes } from './router';

function renderAt(path: string, table: RouteObject[] = routes) {
  const router = createMemoryRouter(table, { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

describe('app routes', () => {
  let http: ReturnType<typeof stubFetch>;

  beforeEach(() => {
    // The layout restores the session once on load; no session here, so the refresh is refused.
    http = stubFetch({
      'GET /actuator/health': () => jsonResponse(200, { status: 'UP' }),
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    });
  });

  it('renders the layout landmarks and the home page at /', async () => {
    renderAt('/');

    const banner = screen.getByRole('banner');
    expect(within(banner).getByText('Digital Wallet')).toBeInTheDocument();
    expect(within(banner).getByText('Portfolio demo — simulated money only.')).toBeInTheDocument();

    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Home' })).toHaveAttribute('aria-current', 'page');

    const main = screen.getByRole('main');
    expect(within(main).getByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument();
    expect(await within(main).findByText('Gateway is up')).toBeInTheDocument();
  });

  it('offers a skip link to the main content', () => {
    renderAt('/');

    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute(
      'href',
      '#main-content',
    );
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
  });

  it('shows the not-found page inside the layout for an unknown path', () => {
    renderAt('/no-such-page');

    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current');
    expect(http.callsTo('GET', '/actuator/health')).toHaveLength(0);
    expect(http.callsTo('POST', '/api/v1/auth/refresh')).toHaveLength(1);
  });

  it('navigates from the not-found page back home', async () => {
    const user = userEvent.setup();
    const router = renderAt('/missing/deep/path');

    await user.click(screen.getByRole('link', { name: 'Go to the home page' }));

    expect(router.state.location.pathname).toBe('/');
    expect(await screen.findByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument();
  });

  it('shows a generic error page without details when a route fails to render', () => {
    // React reports the render error on console.error; keep the test output clean.
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    function Broken(): never {
      throw new Error('secret internal detail');
    }
    const [root] = routes;
    if (!root || root.index) throw new Error('expected the layout route first');
    const table: RouteObject[] = [
      { ...root, children: [...(root.children ?? []), { path: 'broken', element: <Broken /> }] },
    ];

    renderAt('/broken', table);

    expect(
      screen.getByRole('heading', { level: 1, name: 'Something went wrong' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/secret internal detail/)).not.toBeInTheDocument();
  });
});
