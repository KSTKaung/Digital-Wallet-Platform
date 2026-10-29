import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { getGatewayHealth, type HealthResponse } from '../api/health';

type State =
  | { kind: 'loading' }
  | { kind: 'loaded'; health: HealthResponse }
  | { kind: 'error'; message: string };

export function HealthStatus() {
  const [state, setState] = useState<State>({ kind: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    getGatewayHealth(controller.signal)
      .then((health) => setState({ kind: 'loaded', health }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        const message =
          error instanceof ApiError
            ? `Gateway returned ${error.status} (${error.errorCode})`
            : 'Gateway is unreachable';
        setState({ kind: 'error', message });
      });
    return () => controller.abort();
  }, []);

  return (
    <section
      aria-labelledby="health-heading"
      className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
    >
      <h2 id="health-heading" className="mb-2 text-lg font-medium">
        Platform status
      </h2>
      <p
        role="status"
        aria-live="polite"
        className={`font-semibold ${STATUS_CLASSES[statusKind(state)]}`}
      >
        {statusText(state)}
      </p>
    </section>
  );
}

function statusText(state: State): string {
  switch (state.kind) {
    case 'loading':
      return 'Checking gateway…';
    case 'loaded':
      return state.health.status === 'UP' ? 'Gateway is up' : `Gateway is ${state.health.status}`;
    case 'error':
      return state.message;
  }
}

const STATUS_CLASSES = {
  pending: 'text-slate-600 dark:text-slate-400',
  ok: 'text-green-700 dark:text-green-400',
  bad: 'text-red-700 dark:text-red-400',
} as const;

function statusKind(state: State): keyof typeof STATUS_CLASSES {
  if (state.kind === 'loading') return 'pending';
  if (state.kind === 'loaded' && state.health.status === 'UP') return 'ok';
  return 'bad';
}
