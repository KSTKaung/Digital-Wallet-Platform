import { useEffect, useRef } from 'react';

/**
 * Calls `refetch` when the window regains focus (documentations/08 §4D D7: a refresh button and a
 * refetch on focus, no background polling). The latest callback is used without re-subscribing.
 */
export function useRefetchOnFocus(refetch: () => void, enabled = true) {
  const latest = useRef(refetch);

  useEffect(() => {
    latest.current = refetch;
  }, [refetch]);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    const onFocus = () => latest.current();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [enabled]);
}
