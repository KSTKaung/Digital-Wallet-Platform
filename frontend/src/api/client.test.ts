import { describe, expect, it, vi } from 'vitest';
import { ApiError, apiRequest } from './client';

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

describe('apiRequest', () => {
  it('calls the Gateway base URL from VITE_API_BASE_URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { status: 'UP' }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/actuator/health')).resolves.toEqual({ status: 'UP' });
    expect(fetchMock).toHaveBeenCalledWith(
      'http://gateway.test/actuator/health',
      expect.anything(),
    );
  });

  it('uses same-origin relative URLs when VITE_API_BASE_URL is empty', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { status: 'UP' }));
    vi.stubGlobal('fetch', fetchMock);

    await apiRequest('/actuator/health');
    expect(fetchMock).toHaveBeenCalledWith('/actuator/health', expect.anything());
  });

  it('maps the documented error body to ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(409, {
          success: false,
          errorCode: 'INSUFFICIENT_BALANCE',
          message: 'Available balance is lower than the requested amount',
          correlationId: 'c-1',
        }),
      ),
    );

    const error = await apiRequest('/api/v1/transfers').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 409,
      errorCode: 'INSUFFICIENT_BALANCE',
      correlationId: 'c-1',
    });
  });

  it('falls back to SYSTEM_ERROR and the header correlation id for non-JSON errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response('Bad Gateway', { status: 502, headers: { 'X-Correlation-Id': 'c-2' } }),
        ),
    );

    await expect(apiRequest('/api/v1/wallet')).rejects.toMatchObject({
      status: 502,
      errorCode: 'SYSTEM_ERROR',
      correlationId: 'c-2',
    });
  });

  it('exposes Retry-After on 503 SERVICE_DEGRADED', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            503,
            { success: false, errorCode: 'SERVICE_DEGRADED', message: 'Try later' },
            { 'Retry-After': '30' },
          ),
        ),
    );

    await expect(apiRequest('/api/v1/transfers')).rejects.toMatchObject({
      errorCode: 'SERVICE_DEGRADED',
      retryAfterSeconds: 30,
    });
  });

  it('refuses internal paths without calling fetch', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/internal/v1/wallet/movements')).rejects.toThrow(/internal path/);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
