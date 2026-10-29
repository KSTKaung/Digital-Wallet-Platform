import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { HealthStatus } from './HealthStatus';

function stubFetch(response: Response | Error) {
  vi.stubGlobal(
    'fetch',
    response instanceof Error
      ? vi.fn().mockRejectedValue(response)
      : vi.fn().mockResolvedValue(response),
  );
}

describe('HealthStatus', () => {
  it('shows a loading state, then UP', async () => {
    stubFetch(new Response(JSON.stringify({ status: 'UP' }), { status: 200 }));
    render(<HealthStatus />);

    expect(screen.getByRole('status')).toHaveTextContent('Checking gateway');
    expect(await screen.findByText('Gateway is up')).toBeInTheDocument();
  });

  it('shows a non-UP status', async () => {
    stubFetch(new Response(JSON.stringify({ status: 'DOWN' }), { status: 200 }));
    render(<HealthStatus />);

    expect(await screen.findByText('Gateway is DOWN')).toBeInTheDocument();
  });

  it('shows the error code when the gateway returns an error', async () => {
    stubFetch(new Response('', { status: 503 }));
    render(<HealthStatus />);

    expect(await screen.findByText('Gateway returned 503 (SYSTEM_ERROR)')).toBeInTheDocument();
  });

  it('shows unreachable when the request fails', async () => {
    stubFetch(new TypeError('Failed to fetch'));
    render(<HealthStatus />);

    expect(await screen.findByText('Gateway is unreachable')).toBeInTheDocument();
  });
});
