import { apiRequest } from './client';

/** Gateway Actuator health (doc 04 §6.5). */
export interface HealthResponse {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN';
}

export function getGatewayHealth(signal?: AbortSignal): Promise<HealthResponse> {
  return apiRequest<HealthResponse>('/actuator/health', { signal });
}
