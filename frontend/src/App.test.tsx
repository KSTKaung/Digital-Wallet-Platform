import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { errorResponse, jsonResponse, stubFetch } from './test/http';
import { App } from './App';

describe('App', () => {
  it('mounts the route table in a browser router', async () => {
    stubFetch({
      'GET /actuator/health': () => jsonResponse(200, { status: 'UP' }),
      'POST /api/v1/auth/refresh': () => errorResponse(401, 'UNAUTHORIZED'),
    });

    render(<App />);

    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument();
    expect(await screen.findByText('Gateway is up')).toBeInTheDocument();
  });
});
